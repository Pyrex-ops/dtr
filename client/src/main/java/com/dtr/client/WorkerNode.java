package com.dtr.client;
import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.dtr.commonlib.RenderChunk;

@Component
@EnableScheduling
public class WorkerNode  implements org.springframework.boot.CommandLineRunner {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String MASTER_URL = "http://localhost:8080/api/render";
    private final String NODE_NAME = "Node-1";
    private Long currentChunkId = null; // Track the currently assigned chunk for heartbeat

    @Override
    public void run(String... args) throws Exception {
        System.out.println("Worker node started. Polling for work...");
        
        // Continuous worker loop
        while (!Thread.currentThread().isInterrupted()) {
            try {
                boolean processedWork = pollForWork();
                
                if (!processedWork) {
                    System.out.println("["+ LocalDateTime.now() + "] No chunk assigned. Polling again...");
                    // Back-off: Only wait 5 seconds if there was NO work or an error occurred.
                    Thread.sleep(5000); 
                }
                // If processedWork is true, the loop skips the sleep and immediately polls again
                
            } catch (InterruptedException e) {
                System.out.println("Worker interrupted, shutting down.");
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public boolean pollForWork() {
        try {
            RenderChunk chunk = restTemplate.getForObject(MASTER_URL + "/poll?nodeName=" + NODE_NAME, RenderChunk.class);
            System.out.println("Polling for work...");
            if (chunk != null) {
                System.out.println("Received chunk: Job " + chunk.getJobId() + " Frames " + chunk.getStartFrame() + "-" + chunk.getEndFrame());
                boolean success = renderChunkFrameByFrame(chunk);
                String status = success ? "COMPLETED" : "FAILED";
                System.out.println("Reporting chunk " + chunk.getId() + " as " + status);
                restTemplate.postForLocation(MASTER_URL + "/report/" + chunk.getId() + "?status=" + status, null);
                return true;
            }
            else{
                return false;
            }
        } catch (Exception ignored) {
            ignored.printStackTrace();
            System.out.println("No chunk assigned. Polling again...");
            return false;
        }
    }

    private boolean renderChunkFrameByFrame(RenderChunk chunk) {
        this.currentChunkId = chunk.getId();
        for (int frame = chunk.getStartFrame(); frame <= chunk.getEndFrame(); frame++) {
            
            // 1. Check if Master paused or stopped the job
            Boolean isActive = restTemplate.getForObject(MASTER_URL + "/chunk-status/" + chunk.getId(), Boolean.class);
            if (!isActive) {
                System.out.println("Job Paused/Stopped. Aborting chunk.");
                return false; 
            }
            // 2. Render single frame: use chunk-provided command/args (with placeholders)
            List<String> command = new ArrayList<>();
            if (chunk.getCommand() != null && !chunk.getCommand().isEmpty()) {
                command.add(chunk.getCommand());
                if (chunk.getCommandArgs() != null) {
                    for (String arg : chunk.getCommandArgs()) {
                        command.add(substitute(arg, chunk, frame));
                    }
                }
            } else {
                // fallback example: blender -b <file> -f <frame>
                command.add("blender");
                command.add("-b");
                command.add(chunk.getBlendFilePath());
                command.add("-f");
                command.add(String.valueOf(frame));
            }
            boolean rendered = executeCommand(command);
            if (!rendered) return false;

            // 3. ASYNC Upload: Upload the frame in the background while the loop continues
            final int currentFrame = frame;
            CompletableFuture.runAsync(() -> uploadFrame(chunk.getJobId(), currentFrame));
        }
        this.currentChunkId = null;
        return true;
    }

    private boolean executeCommand(List<String> command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            // pb.inheritIO(); // Optional: to see the command output in the console
            return pb.start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String substitute(String token, RenderChunk chunk, int frame) {
        if (token == null) return null;
        String res = token.replace("{file}", chunk.getBlendFilePath() == null ? "" : chunk.getBlendFilePath())
                          .replace("{frame}", String.valueOf(frame))
                          .replace("{jobId}", chunk.getJobId() == null ? "" : String.valueOf(chunk.getJobId()));
        return res;
    }

    private void uploadFrame(Long jobId, int frame) {
        try {
            // TODO - Make the output path configurable or parse it from the chunk's command/args
            File imageFile = new File("/tmp/render_output_" + frame + ".png"); // Default blender output
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(imageFile));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            restTemplate.postForEntity(MASTER_URL + "/upload?jobId=" + jobId + "&frame=" + frame, 
                                       new HttpEntity<>(body, headers), String.class);
            
            System.out.println("Uploaded frame " + frame);
        } catch (Exception e) {
            System.err.println("Failed to upload frame " + frame);
        }
    }


    @Scheduled(fixedRate = 15000)
    private void heartbeat() {
        if (currentChunkId != null) {
            restTemplate.postForLocation(MASTER_URL + "/heartbeat/" + currentChunkId, null);
        }
    }
}