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
    public Long submitJob(String blendFilePath, int totalFrames, String command, List<String> args) {
        RenderJob job = new RenderJob();
        job.setBlendFilePath(blendFilePath);
        job.setTotalFrames(totalFrames);
        job.setStatus("PENDING");
        job = jobRepository.save(job);

        for (int i = 1; i <= totalFrames; i += FRAMES_PER_CHUNK) {
            int endFrame = Math.min(i + FRAMES_PER_CHUNK - 1, totalFrames);
            
            RenderChunk chunk = new RenderChunk();
            chunk.setJobId(job.getId());
            chunk.setBlendFilePath(blendFilePath);
            chunk.setStartFrame(i);
            chunk.setEndFrame(endFrame);
            // propagate optional command/args from job submission
            chunk.setCommand(command);
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
        RenderChunk chunk = chunkRepository.findFirstByStatusOrderByJobIdAsc(ChunkStatus.PENDING);
        
        if (chunk != null) {
            chunk.setStatus(ChunkStatus.IN_PROGRESS);
            chunk.setAssignedNode(nodeName);
            chunk.setLastHeartbeat(LocalDateTime.now());
            chunkRepository.save(chunk);
        }
        return chunk;
    }

    @Transactional
    public void reportChunkStatus(Long chunkId, ChunkStatus status) {
        RenderChunk chunk = chunkRepository.findById(chunkId).orElseThrow();
        chunk.setStatus(status);
        
        if (status == ChunkStatus.FAILED) {
            chunk.setStatus(ChunkStatus.PENDING);
            chunk.setAssignedNode(null);
        }
        chunkRepository.save(chunk);
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