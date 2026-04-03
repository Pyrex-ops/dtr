package com.dtr.server;

import jakarta.persistence.*;

@Entity
public class RenderJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String blendFilePath;

    private int totalFrames;

    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBlendFilePath() {
        return blendFilePath;
    }

    public void setBlendFilePath(String blendFilePath) {
        this.blendFilePath = blendFilePath;
    }

    public int getTotalFrames() {
        return totalFrames;
    }

    public void setTotalFrames(int totalFrames) {
        this.totalFrames = totalFrames;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}