package com.streamarr.server.services.streaming;

import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TerminatingStreamSessionCleanupWorker {

  private static final int BATCH_SIZE = 50;

  private final StreamSessionLifecycleTransactions lifecycleTransactions;
  private final StreamSessionCleanup cleanupService;
  private final StreamSessionTransactionRetry transactionRetry;
  private final Clock clock;
  private final int batchSize;

  @Autowired
  public TerminatingStreamSessionCleanupWorker(
      StreamSessionLifecycleTransactions lifecycleTransactions,
      StreamSessionCleanup cleanupService,
      StreamSessionTransactionRetry transactionRetry,
      Clock clock) {
    this(lifecycleTransactions, cleanupService, transactionRetry, clock, BATCH_SIZE);
  }

  TerminatingStreamSessionCleanupWorker(
      StreamSessionLifecycleTransactions lifecycleTransactions,
      StreamSessionCleanup cleanupService,
      StreamSessionTransactionRetry transactionRetry,
      Clock clock,
      int batchSize) {
    this.lifecycleTransactions = lifecycleTransactions;
    this.cleanupService = cleanupService;
    this.transactionRetry = transactionRetry;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${streaming.cleanup-interval-ms:15000}")
  public void cleanupTerminating() {
    retryPendingTerminations();
    reconcileMissingMediaSources();
    cleanupKnownTerminating();
  }

  private void retryPendingTerminations() {
    List<StreamSessionTermination> intents;
    try {
      intents = transactionRetry.execute(lifecycleTransactions::findTerminationIntents);
    } catch (RuntimeException exception) {
      log.warn("Failed to load stream session termination intents", exception);
      return;
    }
    for (var termination : intents) {
      try {
        transactionRetry.execute(
            () -> lifecycleTransactions.replayTerminationIntent(termination.streamSessionId()));
      } catch (RuntimeException exception) {
        log.warn(
            "Stream session {} terminal transition will be retried",
            termination.streamSessionId(),
            exception);
      }
    }
  }

  private void reconcileMissingMediaSources() {
    try {
      transactionRetry.execute(
          () -> lifecycleTransactions.terminalizeMissingMediaSources(clock.instant()));
    } catch (RuntimeException exception) {
      log.warn("Failed to reconcile stream sessions with missing media", exception);
    }
  }

  private void cleanupKnownTerminating() {
    try {
      var sessionIds =
          transactionRetry.execute(() -> lifecycleTransactions.findTerminatingIds(batchSize));
      while (!sessionIds.isEmpty()) {
        sessionIds.forEach(this::cleanup);
        if (sessionIds.size() < batchSize) {
          return;
        }
        var afterId = sessionIds.getLast();
        sessionIds =
            transactionRetry.execute(
                () -> lifecycleTransactions.findTerminatingIdsAfter(afterId, batchSize));
      }
    } catch (RuntimeException exception) {
      log.warn("Failed to load terminating stream sessions for cleanup", exception);
    }
  }

  private void cleanup(java.util.UUID streamSessionId) {
    try {
      cleanupService.cleanup(streamSessionId);
    } catch (RuntimeException exception) {
      log.warn("Stream session {} cleanup will be retried", streamSessionId, exception);
    }
  }
}
