package com.dtr.client;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.beans.factory.annotation.Value;
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

    private enum RenderResult {
        COMPLETED,
        FAILED,
        ABORTED
    }

    private final RestTemplate restTemplate = new RestTemplate();
    private final String MASTER_URL = "http://localhost:8080/api/render";
    private final String NODE_NAME = loadOrCreateNodeName();
    private Long currentChunkId = null; // Track the currently assigned chunk for heartbeat
    @Value("${dtr.worker.output-path:/tmp/render_output_{jobId}_{frame}.png}")
    private String outputPathPattern;

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
                RenderResult result = renderChunkFrameByFrame(chunk);
                String status = switch (result) {
                    case COMPLETED -> "COMPLETED";
                    case FAILED -> "FAILED";
                    case ABORTED -> "PENDING";
                };
                System.out.println("Reporting chunk " + chunk.getId() + " as " + status);
                restTemplate.postForLocation(MASTER_URL + "/report/" + chunk.getId() + "?status=" + status, null);
                return result != RenderResult.ABORTED;
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

    private RenderResult renderChunkFrameByFrame(RenderChunk chunk) {
        this.currentChunkId = chunk.getId();
        List<CompletableFuture<Boolean>> uploadFutures = new ArrayList<>();
        try {
            if (chunk.getCommand() == null || chunk.getCommand().isBlank()) {
                System.err.println("Missing command for chunk " + chunk.getId());
                return RenderResult.FAILED;
            }

            for (int frame = chunk.getStartFrame(); frame <= chunk.getEndFrame(); frame++) {
                
                // Check if Master paused or stopped the job
                Boolean isActive = restTemplate.getForObject(MASTER_URL + "/chunk-status/" + chunk.getId(), Boolean.class);
                if (!Boolean.TRUE.equals(isActive)) {
                    System.out.println("Job Paused/Stopped. Aborting chunk.");
                    return RenderResult.ABORTED; 
                }
                File imageFile = resolveOutputFile(chunk, frame);
                List<String> command = buildCommand(chunk, frame, imageFile);
                if (!ensureParentDirectory(imageFile)) return RenderResult.FAILED;
                boolean rendered = executeCommand(command);
                if (!rendered) {
                    waitForUploads(uploadFutures);
                    return RenderResult.FAILED;
                }
                if (!imageFile.isFile()) {
                    System.err.println("Command completed but output file was not found: " + imageFile.getAbsolutePath());
                    waitForUploads(uploadFutures);
                    return RenderResult.FAILED;
                }

                // ASYNC Upload: Upload the frame in the background while the loop continues
                final int currentFrame = frame;
                final File currentImageFile = imageFile;
                uploadFutures.add(CompletableFuture.supplyAsync(() -> uploadFrame(chunk.getJobId(), currentFrame, currentImageFile)));
            }
            return waitForUploads(uploadFutures) ? RenderResult.COMPLETED : RenderResult.FAILED;
        } finally {
            this.currentChunkId = null;
        }
    }

    private List<String> buildCommand(RenderChunk chunk, int frame, File outputFile) {
        List<String> command = new ArrayList<>();
        command.add(chunk.getCommand());
        if (chunk.getCommandArgs() != null) {
            for (String arg : chunk.getCommandArgs()) {
                command.add(substitute(arg, chunk, frame, outputFile));
            }
        }
        return command;
    }

    private boolean waitForUploads(List<CompletableFuture<Boolean>> uploadFutures) {
        boolean allUploaded = true;
        for (CompletableFuture<Boolean> uploadFuture : uploadFutures) {
            try {
                allUploaded &= Boolean.TRUE.equals(uploadFuture.join());
            } catch (Exception e) {
                allUploaded = false;
            }
        }
        return allUploaded;
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

    private String loadOrCreateNodeName() {
        Path nodeNamePath = Path.of(System.getProperty("user.home"), ".dtr", "worker-node-name");
        try {
            if (Files.exists(nodeNamePath)) {
                String nodeName = Files.readString(nodeNamePath, StandardCharsets.UTF_8).trim();
                if (!nodeName.isEmpty()) return nodeName;
            }

            Files.createDirectories(nodeNamePath.getParent());
            String nodeName = "Node-" + UUID.randomUUID();
            Files.writeString(nodeNamePath, nodeName, StandardCharsets.UTF_8);
            return nodeName;
        } catch (IOException e) {
            return "Node-" + UUID.randomUUID();
        }
    }

    private String substitute(String token, RenderChunk chunk, int frame, File outputFile) {
        if (token == null) return null;
        String res = token.replace("{file}", chunk.getInputFilePath() == null ? "" : chunk.getInputFilePath())
                          .replace("{input}", chunk.getInputFilePath() == null ? "" : chunk.getInputFilePath())
                          .replace("{inputFile}", chunk.getInputFilePath() == null ? "" : chunk.getInputFilePath())
                          .replace("{output}", outputFile.getAbsolutePath())
                          .replace("{frame}", String.valueOf(frame))
                          .replace("{jobId}", chunk.getJobId() == null ? "" : String.valueOf(chunk.getJobId()));
        return res;
    }

    private File resolveOutputFile(RenderChunk chunk, int frame) {
        boolean hasExplicitOutputPath = chunk.getOutputPath() != null && !chunk.getOutputPath().isBlank();
        String outputPath = hasExplicitOutputPath ? chunk.getOutputPath() : outputPathPattern;
        List<String> args = chunk.getCommandArgs();
        if (!hasExplicitOutputPath && args != null) {
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg == null) continue;
                if (arg.equals("-o") || arg.equals("--output") || arg.equals("--output-file")) {
                    if (i + 1 < args.size() && args.get(i + 1) != null) {
                        outputPath = args.get(i + 1);
                    }
                } else if (arg.startsWith("--output=")) {
                    outputPath = arg.substring("--output=".length());
                } else if (arg.startsWith("--output-file=")) {
                    outputPath = arg.substring("--output-file=".length());
                }
            }
        }
        return new File(resolveFramePattern(substitute(outputPath, chunk, frame, new File("")), frame));
    }

    private boolean ensureParentDirectory(File imageFile) {
        try {
            File parent = imageFile.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            return true;
        } catch (IOException e) {
            System.err.println("Failed to create output directory for " + imageFile.getAbsolutePath());
            return false;
        }
    }

    private String resolveFramePattern(String path, int frame) {
        int hashStart = path.indexOf('#');
        if (hashStart < 0) return path;

        int hashEnd = hashStart;
        while (hashEnd < path.length() && path.charAt(hashEnd) == '#') {
            hashEnd++;
        }
        String paddedFrame = String.format("%0" + (hashEnd - hashStart) + "d", frame);
        return path.substring(0, hashStart) + paddedFrame + path.substring(hashEnd);
    }

    private boolean uploadFrame(Long jobId, int frame, File imageFile) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", new FileSystemResource(imageFile));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            restTemplate.postForEntity(MASTER_URL + "/upload?jobId=" + jobId + "&frame=" + frame, 
                                       new HttpEntity<>(body, headers), String.class);
            
            System.out.println("Uploaded frame " + frame);
            if (!imageFile.delete() && imageFile.exists()) {
                System.err.println("Uploaded frame " + frame + " but failed to delete local file " + imageFile.getAbsolutePath());
            }
            return true;
        } catch (Exception e) {
            System.err.println("Failed to upload frame " + frame);
            return false;
        }
    }


    @Scheduled(fixedRate = 15000)
    private void heartbeat() {
        if (currentChunkId != null) {
            restTemplate.postForLocation(MASTER_URL + "/heartbeat/" + currentChunkId, null);
        }
    }
}
