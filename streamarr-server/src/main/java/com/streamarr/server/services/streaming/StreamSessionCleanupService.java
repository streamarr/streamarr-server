package com.streamarr.server.services.streaming;

import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class StreamSessionCleanupService implements StreamSessionCleanup {

  private final StreamingService streamingService;
  private final StreamSessionLifecycleTransactions lifecycleTransactions;
  private final SegmentStore segmentStore;
  private final MutexFactory<UUID> cleanupMutex;
  private final StreamSessionTransactionRetry transactionRetry;

  public StreamSessionCleanupService(
      StreamingService streamingService,
      StreamSessionLifecycleTransactions lifecycleTransactions,
      SegmentStore segmentStore,
      StreamSessionTransactionRetry transactionRetry,
      MutexFactoryProvider mutexFactoryProvider) {
    this.streamingService = streamingService;
    this.lifecycleTransactions = lifecycleTransactions;
    this.segmentStore = segmentStore;
    this.transactionRetry = transactionRetry;
    this.cleanupMutex = mutexFactoryProvider.getMutexFactory();
  }

  @Override
  public void cleanup(UUID streamSessionId) {
    var lock = cleanupMutex.getMutex(streamSessionId);
    lock.lock();
    try {
      if (!streamingService.terminateRuntime(streamSessionId)) {
        return;
      }
      transactionRetry.execute(() -> lifecycleTransactions.deleteTerminating(streamSessionId));
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void reconcileUnbackedRuntimeAndStorage() {
    var runtimeIds = Set.copyOf(streamingService.snapshotCleanupCandidateIds());
    var storedIds = segmentStore.snapshotStoredSessionIds();
    var existingIds = transactionRetry.execute(lifecycleTransactions::findAllSessionIds);

    runtimeIds.stream().filter(id -> !existingIds.contains(id)).forEach(this::terminateRuntimeOnly);
    storedIds.stream()
        .filter(id -> !existingIds.contains(id))
        .filter(id -> !runtimeIds.contains(id))
        .forEach(this::terminateStoredOrphan);
  }

  private void terminateRuntimeOnly(UUID streamSessionId) {
    try {
      streamingService.terminateRuntime(streamSessionId);
    } catch (RuntimeException exception) {
      log.warn(
          "Runtime-only stream session {} cleanup will be retried", streamSessionId, exception);
    }
  }

  private void terminateStoredOrphan(UUID streamSessionId) {
    try {
      streamingService.terminateRuntime(streamSessionId);
    } catch (RuntimeException exception) {
      log.warn("Stored stream session {} cleanup will be retried", streamSessionId, exception);
    }
  }
}
