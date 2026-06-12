package com.dtr.server;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import com.dtr.commonlib.RenderChunk;
import com.dtr.commonlib.ChunkStatus;

@Service
public class CoordinatorService {

    @Autowired
    private JobRepository jobRepository;
    @Autowired
    private ChunkRepository chunkRepository;

    private static final int FRAMES_PER_CHUNK = 10;

    @Transactional
    public Long submitJob(String inputFilePath, int totalFrames, String command, List<String> args, String outputPath) {
        RenderJob job = new RenderJob();
        job.setInputFilePath(inputFilePath);
        job.setTotalFrames(totalFrames);
        job.setStatus("PENDING");
        job = jobRepository.save(job);

        for (int i = 1; i <= totalFrames; i += FRAMES_PER_CHUNK) {
            int endFrame = Math.min(i + FRAMES_PER_CHUNK - 1, totalFrames);
            
            RenderChunk chunk = new RenderChunk();
            chunk.setJobId(job.getId());
            chunk.setInputFilePath(inputFilePath);
            chunk.setStartFrame(i);
            chunk.setEndFrame(endFrame);
            chunk.setCommand(command);
            chunk.setOutputPath(outputPath);
            if (args != null) {
                chunk.setCommandArgs(args);
            }
            chunk.setStatus(ChunkStatus.PENDING);
            chunkRepository.save(chunk);
        }
        return job.getId();
    }

    @Transactional
    public RenderChunk getNextAvailableChunk(String nodeName) {
        List<RenderChunk> pendingChunks = chunkRepository.findByStatusOrderByJobIdAscStartFrameAsc(ChunkStatus.PENDING);

        for (RenderChunk chunk : pendingChunks) {
            RenderJob job = jobRepository.findById(chunk.getJobId()).orElse(null);
            if (job == null || !canAssign(job.getStatus())) {
                continue;
            }

            if ("PENDING".equals(job.getStatus())) {
                job.setStatus("IN_PROGRESS");
                jobRepository.save(job);
            }

            chunk.setStatus(ChunkStatus.IN_PROGRESS);
            chunk.setAssignedNode(nodeName);
            chunk.setLastHeartbeat(LocalDateTime.now());
            return chunkRepository.save(chunk);
        }

        return null;
    }

    @Transactional
    public RenderChunk reportChunkStatus(Long chunkId, ChunkStatus status) {
        RenderChunk chunk = chunkRepository.findById(chunkId).orElseThrow();

        if (status == ChunkStatus.COMPLETED) {
            chunk.setStatus(ChunkStatus.COMPLETED);
            chunk.setLastHeartbeat(null);
        } else if (status == ChunkStatus.FAILED || status == ChunkStatus.PENDING) {
            chunk.setStatus(ChunkStatus.PENDING);
            chunk.setAssignedNode(null);
            chunk.setLastHeartbeat(null);
        } else {
            chunk.setStatus(status);
        }
        RenderChunk savedChunk = chunkRepository.save(chunk);

        if (savedChunk.getStatus() == ChunkStatus.COMPLETED) {
            completeJobIfAllChunksFinished(savedChunk.getJobId());
        }

        return savedChunk;
    }

    private boolean canAssign(String status) {
        return "PENDING".equals(status) || "IN_PROGRESS".equals(status);
    }

    private void completeJobIfAllChunksFinished(Long jobId) {
        boolean hasUnfinishedChunks = chunkRepository.existsByJobIdAndStatusNot(jobId, ChunkStatus.COMPLETED);
        if (hasUnfinishedChunks) {
            return;
        }

        RenderJob job = jobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.setStatus("COMPLETED");
            jobRepository.save(job);
        }
    }

    @Scheduled(fixedRate = 60000) // Runs every minute
    @Transactional
    public void rescheduleDeadNodes() {
        LocalDateTime timeoutThreshold = LocalDateTime.now().minusMinutes(5);
        List<RenderChunk> deadChunks = chunkRepository.findByStatusAndLastHeartbeatBefore(
                ChunkStatus.IN_PROGRESS, timeoutThreshold);

        for (RenderChunk chunk : deadChunks) {
            System.out.println("Node crashed on chunk " + chunk.getId() + ". Rescheduling...");
            chunk.setStatus(ChunkStatus.PENDING);
            chunk.setAssignedNode(null);
            chunkRepository.save(chunk);
        }
    }

    @Transactional
    public void updateChunkHeartbeat(Long chunkId) {
        RenderChunk chunk = chunkRepository.findById(chunkId).orElseThrow();
        chunk.setLastHeartbeat(LocalDateTime.now());
        chunkRepository.save(chunk);
    }
}
