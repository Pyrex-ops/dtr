package com.dtr.server;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Comparator;
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
import org.springframework.transaction.annotation.Transactional;

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
        RenderChunk chunk = coordinatorService.getNextAvailableChunk(nodeName);
        if (chunk != null) {
            broadcastUpdate("{\"jobId\":" + chunk.getJobId()
                    + ", \"chunkId\":" + chunk.getId()
                    + ", \"event\":\"CHUNK_ASSIGNED\", \"status\":\"IN_PROGRESS\""
                    + ", \"assignedNode\":\"" + chunk.getAssignedNode() + "\"}");
        }
        return chunk;
    }

    @PostMapping("/report/{chunkId}")
    public void reportChunkStatus(@PathVariable Long chunkId, @RequestParam String status) {
        ChunkStatus chunkStatus = ChunkStatus.valueOf(status.toUpperCase());
        RenderChunk chunk = coordinatorService.reportChunkStatus(chunkId, chunkStatus);
        RenderJob job = jobRepository.findById(chunk.getJobId()).orElse(null);
        String jobStatus = job == null ? "" : job.getStatus();
        broadcastUpdate("{\"jobId\":" + chunk.getJobId()
                + ", \"chunkId\":" + chunk.getId()
                + ", \"event\":\"CHUNK_STATUS_CHANGED\", \"status\":\"" + chunk.getStatus()
                + "\", \"jobStatus\":\"" + jobStatus
                + "\", \"assignedNode\":\"" + chunk.getAssignedNode() + "\"}");
    }

    @PostMapping("/heartbeat/{chunkId}")
    public void sendHeartbeat(@PathVariable Long chunkId) {
        coordinatorService.updateChunkHeartbeat(chunkId);
    }

    @PostMapping("/submit-job")
    public Long submitJob(@RequestParam(required = false) String inputFilePath,
                          @RequestParam(required = false) String blendFilePath,
                          @RequestParam int totalFrames,
                          @RequestParam String command,
                          @RequestParam(required = false) List<String> args,
                          @RequestParam(required = false) String outputPath) {
        String resolvedInputFilePath = inputFilePath != null && !inputFilePath.isBlank() ? inputFilePath : blendFilePath;
        if (resolvedInputFilePath == null || resolvedInputFilePath.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inputFilePath is required");
        }
        if (command == null || command.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "command is required");
        }

        Long jobId = coordinatorService.submitJob(resolvedInputFilePath, totalFrames, command, args, outputPath);
        broadcastUpdate("{\"jobId\":" + jobId + ", \"event\":\"JOB_SUBMITTED\"}");
        return jobId;
    }

    @PostMapping("/upload")
    public void receiveFrame(@RequestParam Long jobId, @RequestParam int frame, @RequestParam MultipartFile file) {
        try {
            Path renderDir = Paths.get("renders", jobId.toString());
            Files.createDirectories(renderDir);
            
            Path filePath = renderDir.resolve("frame_" + frame + getExtension(file.getOriginalFilename()));
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
    @GetMapping("/jobs")
    public List<RenderJob> getJobs() {
        return jobRepository.findAll();
    }

    @GetMapping("/jobs/{jobId}/chunks")
    public List<RenderChunk> getJobChunks(@PathVariable Long jobId) {
        return chunkRepository.findByJobIdOrderByStartFrameAsc(jobId);
    }

    @GetMapping("/jobs/{jobId}/frames/{frame}")
    public ResponseEntity<Resource> getRenderedFrame(@PathVariable Long jobId, @PathVariable int frame) {
        Path filePath = findRenderedFramePath(jobId, frame);

        try {
            Resource resource = new UrlResource(filePath.toUri());
            String contentType = Files.probeContentType(filePath);
            MediaType mediaType = contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
            return ResponseEntity.ok().contentType(mediaType).body(resource);
        } catch (IllegalArgumentException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read rendered file", e);
        }
    }

    private Path findRenderedFramePath(Long jobId, int frame) {
        Path renderDir = Paths.get("renders", jobId.toString());
        if (!Files.isDirectory(renderDir)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Rendered file not found");
        }

        String prefix = "frame_" + frame + ".";
        try (var paths = Files.list(renderDir)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .findFirst()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rendered file not found"));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read rendered files", e);
        }
    }

    private String getExtension(String filename) {
        if (filename == null) {
            return ".bin";
        }

        String cleanFilename = Paths.get(filename).getFileName().toString();
        int extensionStart = cleanFilename.lastIndexOf('.');
        if (extensionStart < 0 || extensionStart == cleanFilename.length() - 1) {
            return ".bin";
        }
        return cleanFilename.substring(extensionStart);
    }

    @DeleteMapping("/jobs/{jobId}")
    @Transactional
    public void deleteJob(@PathVariable Long jobId) {
        RenderJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

        if (!isDeletable(job.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only stopped or finished jobs can be deleted");
        }

        chunkRepository.deleteByJobId(jobId);
        jobRepository.delete(job);
        deleteRenderDirectory(jobId);
        broadcastUpdate("{\"jobId\":" + jobId + ", \"event\":\"JOB_DELETED\"}");
    }

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

    private boolean isDeletable(String status) {
        return "CANCELLED".equals(status) || "COMPLETED".equals(status) || "FAILED".equals(status);
    }

    private void deleteRenderDirectory(Long jobId) {
        Path renderDir = Paths.get("renders", jobId.toString());
        if (!Files.exists(renderDir)) {
            return;
        }

        try (var paths = Files.walk(renderDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete render files", e);
        }
    }
}
