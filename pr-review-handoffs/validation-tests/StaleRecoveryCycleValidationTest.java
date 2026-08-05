package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ProducerEnd;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Temporary code-review validation test for a PR #255 finding: a stale exhausted RecoveryCycle can
 * be resurrected by exhaust()'s tail write racing a planned-seek revival, after which a second
 * producer death spins the delivery loop forever (no sleep on the Superseded path). Passing means
 * the defect MANIFESTS.
 */
@Tag("UnitTest")
@DisplayName("VALIDATION: stale recovery cycle resurrection causes delivery livelock (PR #255)")
class StaleRecoveryCycleValidationTest {

  private static final Duration STALL_THRESHOLD = Duration.ofMillis(200);
  private static final ExecutionTargetId TARGET_A = new ExecutionTargetId("worker-a");
  private static final ExecutionTargetId TARGET_B = new ExecutionTargetId("worker-b");

  @Test
  @DisplayName(
      "VALIDATION: exhaust tail re-installs a superseded cycle; the next death then busy-loops"
          + " without ever starting a replacement")
  void validateStaleCycleResurrectionThenLivelock() throws Exception {
    var transcodeExecutor = new FakeTranscodeExecutor();
    var countingExecutor = new CountingExecutor(transcodeExecutor);
    var segmentStore = new TrapSegmentStore();
    var runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(STALL_THRESHOLD)
            .build();
    var lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(countingExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(new MutexFactory<>())
            .build();
    var coordinator =
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(runtimeRegistry)
            .segmentStore(segmentStore)
            .transcodeExecutor(countingExecutor)
            .producerLifecycle(lifecycle)
            .properties(properties)
            .clock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
            .pollInterval(Duration.ofMillis(20))
            .build();

    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);

    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);

    // Thread A exhausts recovery for attempt X and gets trapped between markExhausted (variant now
    // FAILED) and the tail synchronized block that re-installs the cycle.
    var exhaustOutcome = new AtomicReference<SegmentDelivery>();
    var exhauster =
        new Thread(
            () ->
                exhaustOutcome.set(
                    coordinator.deliver(sessionId, StreamSession.defaultVariant(), "segment0.ts")),
            "validation-exhauster");
    segmentStore.armTrap(
        exhauster, () -> session.getHandle().status() == TranscodeStatus.FAILED);
    exhauster.start();
    assertThat(segmentStore.reachedTrap.await(5, TimeUnit.SECONDS))
        .as("exhauster reached the window between markExhausted and the tail cycle write")
        .isTrue();

    // Thread B performs a planned seek while A is trapped: the FAILED variant is revived with a
    // fresh ACTIVE attempt Y, and B's syncProgress clears state.cycle and tracks Y.
    var seekOutcome = new AtomicReference<SegmentDelivery>();
    var seeker =
        new Thread(
            () ->
                seekOutcome.set(
                    coordinator.deliver(sessionId, StreamSession.defaultVariant(), "segment50.ts")),
            "validation-seeker");
    seeker.start();
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> session.getHandle().status() == TranscodeStatus.ACTIVE);
    Thread.sleep(200);
    seeker.interrupt();
    seeker.join(2000);
    assertThat(seekOutcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);

    // Release A: its tail write finds state.cycle == null and resurrects the exhausted cycle for
    // superseded attempt X, then answers 503 although a healthy ACTIVE producer exists.
    segmentStore.releaseTrap.countDown();
    exhauster.join(2000);
    assertThat(exhaustOutcome.get()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);

    // Attempt Y now dies without publishing. Targets would accept a replacement — correct behavior
    // opens a fresh cycle and recovers (or terminally fails). Instead the stale cycle's
    // exhausted snapshot answers every pass with Superseded and the loop never sleeps.
    transcodeExecutor.acceptTarget(TARGET_A);
    transcodeExecutor.acceptTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);
    countingExecutor.variantLivenessChecks.set(0);
    var targetedStartsBefore = transcodeExecutor.getStartedTargets().size();

    var spinnerOutcome = new AtomicReference<SegmentDelivery>();
    var spinner =
        new Thread(
            () ->
                spinnerOutcome.set(
                    coordinator.deliver(sessionId, StreamSession.defaultVariant(), "segment50.ts")),
            "validation-spinner");
    spinner.start();
    Thread.sleep(1500);

    assertThat(spinner.isAlive()).as("delivery neither recovers nor terminates").isTrue();
    assertThat(transcodeExecutor.getStartedTargets())
        .as("no replacement is ever attempted despite willing targets")
        .hasSize(targetedStartsBefore);
    assertThat(countingExecutor.variantLivenessChecks.get())
        .as("liveness checked far beyond any 20ms-poll cadence: the loop is a hot spin")
        .isGreaterThan(1000);

    // Only destroying the session unsticks the waiter.
    runtimeRegistry.removeById(sessionId);
    spinner.join(2000);
    assertThat(spinnerOutcome.get()).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  /** Blocks one specific thread at segmentExists while the armed condition holds, exactly once. */
  private static final class TrapSegmentStore extends FakeSegmentStore {

    private final CountDownLatch reachedTrap = new CountDownLatch(1);
    private final CountDownLatch releaseTrap = new CountDownLatch(1);
    private final AtomicBoolean tripped = new AtomicBoolean();
    private volatile Thread trappedThread;
    private volatile BooleanSupplier trapCondition;

    private void armTrap(Thread thread, BooleanSupplier condition) {
      trappedThread = thread;
      trapCondition = condition;
    }

    @Override
    public boolean segmentExists(UUID sessionId, String segmentName) {
      if (Thread.currentThread() == trappedThread
          && trapCondition.getAsBoolean()
          && tripped.compareAndSet(false, true)) {
        reachedTrap.countDown();
        try {
          releaseTrap.await();
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
        }
      }
      return super.segmentExists(sessionId, segmentName);
    }
  }

  /** Counts per-variant liveness checks to distinguish a hot spin from the 20ms poll loop. */
  private static final class CountingExecutor implements TranscodeExecutor {

    private final FakeTranscodeExecutor delegate;
    private final AtomicLong variantLivenessChecks = new AtomicLong();

    private CountingExecutor(FakeTranscodeExecutor delegate) {
      this.delegate = delegate;
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      return delegate.start(request);
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
      return delegate.start(request, target);
    }

    @Override
    public void stop(UUID sessionId) {
      delegate.stop(sessionId);
    }

    @Override
    public void stopVariant(UUID sessionId, String variantLabel) {
      delegate.stopVariant(sessionId, variantLabel);
    }

    @Override
    public boolean isRunning(UUID sessionId) {
      return delegate.isRunning(sessionId);
    }

    @Override
    public boolean isRunning(UUID sessionId, String variantLabel) {
      variantLivenessChecks.incrementAndGet();
      return delegate.isRunning(sessionId, variantLabel);
    }

    @Override
    public boolean isHealthy() {
      return delegate.isHealthy();
    }

    @Override
    public int availableSlots() {
      return delegate.availableSlots();
    }

    @Override
    public Set<ExecutionTargetId> executionTargets() {
      return delegate.executionTargets();
    }

    @Override
    public Optional<ProducerEnd> deathEvidence(
        UUID sessionId, String variantLabel, UUID expectedAttemptId) {
      return delegate.deathEvidence(sessionId, variantLabel, expectedAttemptId);
    }
  }
}
