package com.streamarr.server.repositories.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FileProcessingTaskRepositoryCustom {

  Optional<FileProcessingTask> claimNextTask(String ownerInstanceId, Instant leaseExpiresAt);

  List<FileProcessingTask> reclaimOrphanedTasks(
      String ownerInstanceId, Instant leaseExpiresAt, Instant now, int limit);

  int extendLeases(String ownerInstanceId, Instant newLeaseExpiresAt);
}
