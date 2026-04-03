package com.dtr.server;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<RenderJob, Long> {
}
