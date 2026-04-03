package com.dtr.server;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

import com.dtr.commonlib.RenderChunk;
import com.dtr.commonlib.ChunkStatus;

public interface ChunkRepository extends JpaRepository<RenderChunk, Long> {
    RenderChunk findFirstByStatusOrderByJobIdAsc(ChunkStatus status);
    List<RenderChunk> findByStatusAndLastHeartbeatBefore(ChunkStatus status, LocalDateTime before);
}
