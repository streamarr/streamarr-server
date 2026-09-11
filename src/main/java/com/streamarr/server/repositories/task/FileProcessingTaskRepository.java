package com.streamarr.server.repositories.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FileProcessingTaskRepository
    extends JpaRepository<FileProcessingTask, UUID>, FileProcessingTaskRepositoryCustom {}
