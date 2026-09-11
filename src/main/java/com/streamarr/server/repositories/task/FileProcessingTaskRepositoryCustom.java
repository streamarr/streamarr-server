package com.streamarr.server.repositories.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileProcessingTaskRepositoryCustom {

  /** Active rows left by the watcher before probes moved to db-scheduler, in id order. */
  List<FileProcessingTask> findLegacyTasks(Optional<UUID> afterId, int limit);

  void cancelTask(String filepathUri);

  Optional<FileProcessingTask> completeTask(UUID taskId, Instant completedOn);

  Optional<FileProcessingTask> failTask(UUID taskId, String errorMessage, Instant completedOn);
}
