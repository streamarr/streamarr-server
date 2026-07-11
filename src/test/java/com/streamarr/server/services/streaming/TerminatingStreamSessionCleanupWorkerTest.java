package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.repositories.streaming.MediaStreamTermination;
import com.streamarr.server.repositories.streaming.PlaybackRequestAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionAuthority;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Terminating Stream Session Cleanup Worker Tests")
class TerminatingStreamSessionCleanupWorkerTest {

  @Test
  @DisplayName("Should continue cleanup when one terminating session fails")
  void shouldContinueCleanupWhenOneTerminatingSessionFails() {
    var failingId = UUID.randomUUID();
    var succeedingId = UUID.randomUUID();
    var lifecycle = new CleanupLifecycle(List.of(failingId, succeedingId));
    var cleanup = new IsolatingCleanup(failingId);
    var worker =
        new TerminatingStreamSessionCleanupWorker(
            lifecycle, cleanup, new StreamSessionTransactionRetry(_ -> {}), Clock.systemUTC());

    worker.cleanupTerminating();

    assertThat(cleanup.attemptedIds).containsExactly(failingId, succeedingId);
    assertThat(cleanup.cleanedIds).containsExactly(succeedingId);
  }

  @Test
  @DisplayName("Should perform no external cleanup when terminating query fails")
  void shouldPerformNoExternalCleanupWhenTerminatingQueryFails() {
    var cleanup = new IsolatingCleanup(Set.of());
    var worker =
        new TerminatingStreamSessionCleanupWorker(
            new CleanupLifecycle(null),
            cleanup,
            new StreamSessionTransactionRetry(_ -> {}),
            Clock.systemUTC());

    worker.cleanupTerminating();

    assertThat(cleanup.attemptedIds).isEmpty();
  }

  @Test
  @DisplayName("Should clean existing terminal rows when missing-media reconciliation fails")
  void shouldCleanExistingTerminalRowsWhenMissingMediaReconciliationFails() {
    var terminatingId = UUID.randomUUID();
    var cleanup = new IsolatingCleanup(Set.of());
    var worker =
        new TerminatingStreamSessionCleanupWorker(
            new CleanupLifecycle(List.of(terminatingId), true),
            cleanup,
            new StreamSessionTransactionRetry(_ -> {}),
            Clock.systemUTC());

    worker.cleanupTerminating();

    assertThat(cleanup.attemptedIds).containsExactly(terminatingId);
  }

  @Test
  @DisplayName("Should attempt rows after a full batch of persistent cleanup failures")
  void shouldAttemptRowsAfterFullBatchOfPersistentCleanupFailures() {
    var terminatingIds = IntStream.rangeClosed(1, 3).mapToObj(index -> new UUID(0, index)).toList();
    var cleanup = new IsolatingCleanup(Set.copyOf(terminatingIds.subList(0, 2)));
    var worker =
        new TerminatingStreamSessionCleanupWorker(
            new CleanupLifecycle(terminatingIds),
            cleanup,
            new StreamSessionTransactionRetry(_ -> {}),
            Clock.systemUTC(),
            2);

    worker.cleanupTerminating();
    worker.cleanupTerminating();

    assertThat(cleanup.attemptedIds).contains(terminatingIds.getLast());
  }

  @Test
  @DisplayName("Should retry a durable terminal transition before cleanup discovery")
  void shouldRetryDurableTerminalTransitionBeforeCleanupDiscovery() {
    var streamSessionId = UUID.randomUUID();
    var lifecycle = new CleanupLifecycle(List.of(streamSessionId));
    lifecycle.recordTerminationIntent(
        StreamSessionTermination.builder()
            .streamSessionId(streamSessionId)
            .reason(StreamSessionTerminalReason.STARTUP_FAILURE)
            .terminalAt(Instant.now())
            .build());
    var cleanup = new IsolatingCleanup(Set.of());
    var worker =
        new TerminatingStreamSessionCleanupWorker(
            lifecycle, cleanup, new StreamSessionTransactionRetry(_ -> {}), Clock.systemUTC());

    worker.cleanupTerminating();

    assertThat(lifecycle.findTerminationIntents()).isEmpty();
    assertThat(cleanup.cleanedIds).containsExactly(streamSessionId);
  }

  private static final class IsolatingCleanup implements StreamSessionCleanup {

    private final Set<UUID> failingIds;
    private final List<UUID> attemptedIds = new ArrayList<>();
    private final List<UUID> cleanedIds = new ArrayList<>();

    private IsolatingCleanup(UUID failingId) {
      this(failingId == null ? Set.of() : Set.of(failingId));
    }

    private IsolatingCleanup(Set<UUID> failingIds) {
      this.failingIds = failingIds;
    }

    @Override
    public void cleanup(UUID streamSessionId) {
      attemptedIds.add(streamSessionId);
      if (failingIds.contains(streamSessionId)) {
        throw new IllegalStateException("simulated cleanup failure");
      }
      cleanedIds.add(streamSessionId);
    }
  }

  private static final class CleanupLifecycle implements StreamSessionLifecycleTransactions {

    private final List<UUID> terminatingIds;
    private final boolean reconciliationFails;
    private final List<StreamSessionTermination> terminationIntents = new ArrayList<>();

    private CleanupLifecycle(List<UUID> terminatingIds) {
      this(terminatingIds, false);
    }

    private CleanupLifecycle(List<UUID> terminatingIds, boolean reconciliationFails) {
      this.terminatingIds = terminatingIds;
      this.reconciliationFails = reconciliationFails;
    }

    @Override
    public List<UUID> findTerminatingIds(int limit) {
      if (terminatingIds == null) {
        throw new IllegalStateException("simulated database failure");
      }
      return terminatingIds.subList(0, Math.min(limit, terminatingIds.size()));
    }

    @Override
    public List<UUID> findTerminatingIdsAfter(UUID afterId, int limit) {
      var start = terminatingIds.indexOf(afterId) + 1;
      var end = Math.min(start + limit, terminatingIds.size());
      return terminatingIds.subList(start, end);
    }

    @Override
    public Optional<Instant> admit(
        StreamSessionAuthority authority, java.time.Duration provisioningTimeout) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean activate(
        StreamSessionAuthority authority, java.time.Duration provisioningTimeout) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Instant> touchIfPlaybackRequestMatches(PlaybackRequestAuthority authority) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> terminalizeByMediaFiles(MediaStreamTermination termination) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> terminalizeMissingMediaSources(Instant terminalAt) {
      if (reconciliationFails) {
        throw new IllegalStateException("simulated reconciliation failure");
      }
      return List.of();
    }

    @Override
    public boolean terminalize(StreamSessionTermination termination) {
      return true;
    }

    @Override
    public boolean recordTerminationIntent(StreamSessionTermination termination) {
      terminationIntents.add(termination);
      return true;
    }

    @Override
    public List<StreamSessionTermination> findTerminationIntents() {
      return List.copyOf(terminationIntents);
    }

    @Override
    public boolean completeCreation(UUID streamSessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean replayTerminationIntent(UUID streamSessionId) {
      return terminationIntents.removeIf(
          termination -> termination.streamSessionId().equals(streamSessionId));
    }

    @Override
    public boolean deleteTerminationIntent(UUID streamSessionId) {
      return terminationIntents.removeIf(
          termination -> termination.streamSessionId().equals(streamSessionId));
    }

    @Override
    public boolean deleteTerminating(UUID streamSessionId) {
      throw new UnsupportedOperationException();
    }
  }
}
