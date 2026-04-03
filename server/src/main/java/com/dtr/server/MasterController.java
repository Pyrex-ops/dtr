package com.dtr.server;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.dtr.commonlib.RenderChunk;
import com.dtr.commonlib.ChunkStatus;


@SpringBootApplication
@EnableScheduling
@RestController
@RequestMapping("/api/render")
@CrossOrigin(origins = "*") // Allow React to connect
@EntityScan(basePackages = {"com.dtr.server", "com.dtr.commonlib"})
public class MasterController {

    // Keep track of all connected React clients
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private CoordinatorService coordinatorService;


	public static void main(String[] args) {
		SpringApplication.run(MasterController.class, args);
	}

    // --- REALTIME SSE STREAM ---
    @GetMapping("/stream")
    public SseEmitter streamUpdates() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE); // Keep connection open
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        return emitter;
    }

    // Helper to push updates to React
    private void broadcastUpdate(String message) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("job_update").data(message));
            } catch (Exception e) {
                emitters.remove(emitter);
            }
        }
    }

    // --- WORKER ENDPOINTS ---
    @GetMapping("/poll")
    public RenderChunk pollForWork(@RequestParam String nodeName) {
        System.out.println("[" + LocalDateTime.now() + "]" + " Worker " + nodeName + " is polling for work...");
        return coordinatorService.getNextAvailableChunk(nodeName);
    }

    @PostMapping("/report/{chunkId}")
    public void reportChunkStatus(@PathVariable Long chunkId, @RequestParam String status) {
        ChunkStatus chunkStatus = ChunkStatus.valueOf(status.toUpperCase());
        coordinatorService.reportChunkStatus(chunkId, chunkStatus);
    }

    @PostMapping("/heartbeat/{chunkId}")
    public void sendHeartbeat(@PathVariable Long chunkId) {
        coordinatorService.updateChunkHeartbeat(chunkId);
    }

    @PostMapping("/submit-job")
    public Long submitJob(@RequestParam String blendFilePath,
                          @RequestParam int totalFrames,
                          @RequestParam(required = false) String command,
                          @RequestParam(required = false) List<String> args) {
        return coordinatorService.submitJob(blendFilePath, totalFrames, command, args);
    }

    @PostMapping("/upload")
    public void receiveFrame(@RequestParam Long jobId, @RequestParam int frame, @RequestParam MultipartFile file) {
        try {
            Path renderDir = Paths.get("renders", jobId.toString());
            Files.createDirectories(renderDir);
            
            Path filePath = renderDir.resolve("frame_" + frame + ".png");
            Files.write(filePath, file.getBytes());
            
            System.out.println("Received frame " + frame + " for job " + jobId + " saved to " + filePath);
        } catch (IOException e) {
            System.err.println("Failed to save frame " + frame + " for job " + jobId + ": " + e.getMessage());
            throw new RuntimeException("Failed to save file", e);
        }
        
        broadcastUpdate("{\"jobId\":" + jobId + ", \"event\":\"FRAME_COMPLETE\", \"frame\":" + frame + "}");
    }

    @GetMapping("/chunk-status/{chunkId}")
    public boolean checkChunkStatus(@PathVariable Long chunkId) {
        RenderChunk chunk = chunkRepository.findById(chunkId).orElse(null);
        if (chunk == null) {
            return false; // Chunk not found, stop rendering
        }
        
        RenderJob job = jobRepository.findById(chunk.getJobId()).orElse(null);
        if (job == null) {
            return false; // Job not found, stop rendering
        }
        
        String status = job.getStatus();
        // If Job is PAUSED or CANCELLED, return false.
        // Returning true means the worker should keep rendering.
        return !"PAUSED".equals(status) && !"CANCELLED".equals(status);
    }

    // --- FRONTEND CONTROL ENDPOINTS ---
    @PostMapping("/{jobId}/pause")
    public void pauseJob(@PathVariable Long jobId) {
        RenderJob job = jobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.setStatus("PAUSED");
            jobRepository.save(job);
        }
        broadcastUpdate("{\"jobId\":" + jobId + ", \"event\":\"STATUS_CHANGED\", \"status\":\"PAUSED\"}");
    }

    @PostMapping("/{jobId}/resume")
    public void resumeJob(@PathVariable Long jobId) {
        RenderJob job = jobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.setStatus("IN_PROGRESS");
            jobRepository.save(job);
        }
        broadcastUpdate("{\"jobId\":" + jobId + ", \"event\":\"STATUS_CHANGED\", \"status\":\"IN_PROGRESS\"}");
    }

    @PostMapping("/{jobId}/stop")
    public void stopJob(@PathVariable Long jobId) {
        RenderJob job = jobRepository.findById(jobId).orElse(null);
        if (job != null) {
            job.setStatus("CANCELLED");
            jobRepository.save(job);
        }
        broadcastUpdate("{\"jobId\":" + jobId + ", \"event\":\"STATUS_CHANGED\", \"status\":\"CANCELLED\"}");
    }
}