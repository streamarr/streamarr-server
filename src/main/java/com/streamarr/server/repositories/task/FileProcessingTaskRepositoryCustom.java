package com.streamarr.server.repositories.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.ProbeClaim;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileProcessingTaskRepositoryCustom {

  List<FileProcessingTask> findLegacyTasks(Optional<UUID> afterId, int limit);

  UUID enqueueProbe(ProbeRequest request);

  void cancelTask(String filepathUri);

  Optional<ProbeClaim> claimProbeTask(String ownerInstanceId, Instant leaseExpiresAt);

  boolean retryProbe(ProbeClaim claim, String errorMessage, Instant retryAt);

  boolean publishProbe(ProbePublication publication);

  boolean completeProbe(ProbeClaim claim);

  boolean failProbe(ProbeClaim claim, String errorMessage);

  boolean rescheduleProbe(ProbeClaim claim, ProbeRequest replacement);

  boolean renewProbe(ProbeClaim claim, Instant leaseExpiresAt);

  Optional<FileProcessingTask> claimNextTask(String ownerInstanceId, Instant leaseExpiresAt);

  List<FileProcessingTask> reclaimOrphanedTasks(
      String ownerInstanceId, Instant leaseExpiresAt, Instant now, int limit);

  int extendLeases(String ownerInstanceId, Instant newLeaseExpiresAt);

  Optional<FileProcessingTask> completeTask(UUID taskId, Instant completedOn);

  Optional<FileProcessingTask> failTask(UUID taskId, String errorMessage, Instant completedOn);
}
