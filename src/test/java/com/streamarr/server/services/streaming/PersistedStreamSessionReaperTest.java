package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSessionTerminalReason;
import com.streamarr.server.repositories.streaming.StreamSessionTermination;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Persisted Stream Session Reaper Tests")
class PersistedStreamSessionReaperTest {

  @Test
  @DisplayName("Should continue cleanup when one terminating session fails")
  void shouldContinueCleanupWhenOneTerminatingSessionFails() {
    var failingId = UUID.randomUUID();
    var succeedingId = UUID.randomUUID();
    var lifecycle = new CleanupLifecycle(List.of(failingId, succeedingId));
    var cleanup = new IsolatingCleanup(failingId);
    var worker = reaper(lifecycle, cleanup);

    worker.reapPersistedSessions();

    assertThat(cleanup.attemptedIds).containsExactly(failingId, succeedingId);
    assertThat(cleanup.cleanedIds).containsExactly(succeedingId);
  }

  @Test
  @DisplayName("Should perform no external cleanup when terminating query fails")
  void shouldPerformNoExternalCleanupWhenTerminatingQueryFails() {
    var cleanup = new IsolatingCleanup(Set.of());
    var worker = reaper(new CleanupLifecycle(null), cleanup);

    worker.reapPersistedSessions();

    assertThat(cleanup.attemptedIds).isEmpty();
  }

  @Test
  @DisplayName("Should clean existing terminal rows when missing-media reconciliation fails")
  void shouldCleanExistingTerminalRowsWhenMissingMediaReconciliationFails() {
    var terminatingId = UUID.randomUUID();
    var cleanup = new IsolatingCleanup(Set.of());
    var worker = reaper(new CleanupLifecycle(List.of(terminatingId), true), cleanup);

    worker.reapPersistedSessions();

    assertThat(cleanup.attemptedIds).containsExactly(terminatingId);
  }

  @Test
  @DisplayName("Should attempt rows after a full batch of persistent cleanup failures")
  void shouldAttemptRowsAfterFullBatchOfPersistentCleanupFailures() {
    var terminatingIds = IntStream.rangeClosed(1, 3).mapToObj(index -> new UUID(0, index)).toList();
    var cleanup = new IsolatingCleanup(Set.copyOf(terminatingIds.subList(0, 2)));
    var worker =
        new PersistedStreamSessionReaper(
            new CleanupLifecycle(terminatingIds),
            cleanup,
            new StreamSessionTransactionRetry(_ -> {}),
            Clock.systemUTC(),
            properties(),
            2);

    worker.reapPersistedSessions();
    worker.reapPersistedSessions();

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
    var worker = reaper(lifecycle, cleanup);

    worker.reapPersistedSessions();

    assertThat(lifecycle.findTerminationIntents()).isEmpty();
    assertThat(cleanup.cleanedIds).containsExactly(streamSessionId);
  }

  @Test
  @DisplayName("Should continue cleanup when termination intents cannot be loaded")
  void shouldContinueCleanupWhenTerminationIntentsCannotBeLoaded() {
    var terminatingId = UUID.randomUUID();
    var lifecycle = new CleanupLifecycle(List.of(terminatingId));
    lifecycle.failIntentLoading();
    var cleanup = new IsolatingCleanup(Set.of());
    var worker = reaper(lifecycle, cleanup);

    worker.reapPersistedSessions();

    assertThat(lifecycle.reconciliationAttempts).isEqualTo(1);
    assertThat(cleanup.cleanedIds).containsExactly(terminatingId);
  }

  @Test
  @DisplayName("Should continue replaying termination intents when one replay fails")
  void shouldContinueReplayingTerminationIntentsWhenOneReplayFails() {
    var failingId = UUID.randomUUID();
    var succeedingId = UUID.randomUUID();
    var lifecycle = new CleanupLifecycle(List.of());
    lifecycle.recordTerminationIntent(terminationIntent(failingId));
    lifecycle.recordTerminationIntent(terminationIntent(succeedingId));
    lifecycle.failReplayFor(failingId);
    var worker = reaper(lifecycle, new IsolatingCleanup(Set.of()));

    worker.reapPersistedSessions();

    assertThat(lifecycle.replayAttemptedIds).containsExactly(failingId, succeedingId);
    assertThat(lifecycle.findTerminationIntents())
        .extracting(StreamSessionTermination::streamSessionId)
        .containsExactly(failingId);
  }

  @Test
  @DisplayName("Should terminalize retention-expired sessions before cleanup")
  void shouldTerminalizeRetentionExpiredSessionsBeforeCleanup() {
    var streamSessionId = UUID.randomUUID();
    var lifecycle = new CleanupLifecycle(new ArrayList<>());
    lifecycle.expire(streamSessionId);
    var cleanup = new IsolatingCleanup(Set.of());

    reaper(lifecycle, cleanup).reapPersistedSessions();

    assertThat(cleanup.cleanedIds).containsExactly(streamSessionId);
    assertThat(lifecycle.observedRetention).isEqualTo(properties().sessionRetention());
  }

  @Test
  @DisplayName("Should reconcile unbacked runtime and storage before terminal cleanup")
  void shouldReconcileUnbackedRuntimeAndStorageBeforeTerminalCleanup() {
    var cleanup = new IsolatingCleanup(Set.of());

    reaper(new CleanupLifecycle(List.of()), cleanup).reapPersistedSessions();

    assertThat(cleanup.reconciled).isTrue();
  }

  @Test
  @DisplayName("Should continue reconciliation and cleanup when retention expiry fails")
  void shouldContinueReconciliationAndCleanupWhenRetentionExpiryFails() {
    var terminatingId = UUID.randomUUID();
    var lifecycle = new CleanupLifecycle(List.of(terminatingId));
    lifecycle.failExpiration();
    var cleanup = new IsolatingCleanup(Set.of());

    reaper(lifecycle, cleanup).reapPersistedSessions();

    assertThat(cleanup.reconciled).isTrue();
    assertThat(cleanup.cleanedIds).containsExactly(terminatingId);
  }

  @Test
  @DisplayName("Should continue terminal cleanup when orphan reconciliation fails")
  void shouldContinueTerminalCleanupWhenOrphanReconciliationFails() {
    var terminatingId = UUID.randomUUID();
    var cleanup = new IsolatingCleanup(Set.of());
    cleanup.failReconciliation();

    reaper(new CleanupLifecycle(List.of(terminatingId)), cleanup).reapPersistedSessions();

    assertThat(cleanup.cleanedIds).containsExactly(terminatingId);
  }

  private static PersistedStreamSessionReaper reaper(
      StreamSessionLifecycleTransactions lifecycle, StreamSessionCleanup cleanup) {
    return new PersistedStreamSessionReaper(
        lifecycle,
        cleanup,
        new StreamSessionTransactionRetry(_ -> {}),
        Clock.systemUTC(),
        properties());
  }

  private static StreamingProperties properties() {
    return StreamingProperties.builder().sessionRetention(Duration.ofHours(24)).build();
  }

  private static StreamSessionTermination terminationIntent(UUID streamSessionId) {
    return StreamSessionTermination.builder()
        .streamSessionId(streamSessionId)
        .reason(StreamSessionTerminalReason.STARTUP_FAILURE)
        .terminalAt(Instant.now())
        .build();
  }

  private static final class IsolatingCleanup implements StreamSessionCleanup {

    private final Set<UUID> failingIds;
    private final List<UUID> attemptedIds = new ArrayList<>();
    private final List<UUID> cleanedIds = new ArrayList<>();
    private boolean reconciled;
    private boolean reconciliationFails;

    private IsolatingCleanup(UUID failingId) {
      this(failingId == null ? Set.of() : Set.of(failingId));
    }

    private IsolatingCleanup(Set<UUID> failingIds) {
      this.failingIds = failingIds;
    }

    private void failReconciliation() {
      reconciliationFails = true;
    }

    @Override
    public void cleanup(UUID streamSessionId) {
      attemptedIds.add(streamSessionId);
      if (failingIds.contains(streamSessionId)) {
        throw new IllegalStateException("simulated cleanup failure");
      }
      cleanedIds.add(streamSessionId);
    }

    @Override
    public void reconcileUnbackedRuntimeAndStorage() {
      reconciled = true;
      if (reconciliationFails) {
        throw new IllegalStateException("simulated orphan reconciliation failure");
      }
    }
  }

  private static final class CleanupLifecycle
      extends UnsupportedStreamSessionLifecycleTransactions {

    private final List<UUID> terminatingIds;
    private final boolean reconciliationFails;
    private final List<StreamSessionTermination> terminationIntents = new ArrayList<>();
    private final List<UUID> replayAttemptedIds = new ArrayList<>();
    private final Set<UUID> failingReplayIds = new HashSet<>();
    private boolean intentLoadingFails;
    private int reconciliationAttempts;
    private UUID expiringId;
    private Duration observedRetention;
    private boolean expirationFails;

    private CleanupLifecycle(List<UUID> terminatingIds) {
      this(terminatingIds, false);
    }

    private CleanupLifecycle(List<UUID> terminatingIds, boolean reconciliationFails) {
      this.terminatingIds = terminatingIds;
      this.reconciliationFails = reconciliationFails;
    }

    private void failIntentLoading() {
      intentLoadingFails = true;
    }

    private void failReplayFor(UUID streamSessionId) {
      failingReplayIds.add(streamSessionId);
    }

    private void expire(UUID streamSessionId) {
      expiringId = streamSessionId;
    }

    private void failExpiration() {
      expirationFails = true;
    }

    @Override
    public List<UUID> terminalizeExpiredActiveSessions(Duration retention, int limit) {
      observedRetention = retention;
      if (expirationFails) {
        throw new IllegalStateException("simulated retention expiry failure");
      }
      if (expiringId == null) {
        return List.of();
      }
      terminatingIds.add(expiringId);
      return List.of(expiringId);
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
    public List<UUID> terminalizeMissingMediaSources(Instant terminalAt) {
      reconciliationAttempts++;
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
      if (intentLoadingFails) {
        throw new IllegalStateException("simulated intent loading failure");
      }
      return List.copyOf(terminationIntents);
    }

    @Override
    public boolean replayTerminationIntent(UUID streamSessionId) {
      replayAttemptedIds.add(streamSessionId);
      if (failingReplayIds.contains(streamSessionId)) {
        throw new IllegalStateException("simulated intent replay failure");
      }
      return terminationIntents.removeIf(
          termination -> termination.streamSessionId().equals(streamSessionId));
    }

    @Override
    public boolean deleteTerminationIntent(UUID streamSessionId) {
      return terminationIntents.removeIf(
          termination -> termination.streamSessionId().equals(streamSessionId));
    }
  }
}
