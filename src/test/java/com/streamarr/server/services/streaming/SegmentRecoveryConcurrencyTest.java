package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.StreamingRigFixture.StreamingRig;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Segment Recovery Concurrency Tests")
class SegmentRecoveryConcurrencyTest {
  private FakeSegmentStore store;
  private FakeRuntimeStreamSessionRegistry registry;
  private MutableClock clock;
  private StreamingProperties properties;

  @BeforeEach
  void setUp() {
    store = new FakeSegmentStore();
    registry = new FakeRuntimeStreamSessionRegistry();
    clock = new MutableClock();
    properties = StreamingProperties.builder().build();
  }

  private StreamingRig rigWith(FakeTranscodeExecutor executor) {
    return StreamingRigFixture.streamingRigBuilder()
        .transcodeExecutor(executor)
        .segmentStore(store)
        .runtimeRegistry(registry)
        .properties(properties)
        .clock(clock)
        .pollInterval(Duration.ofMillis(1))
        .build();
  }

  @Test
  @DisplayName(
      "Should preserve the final producer when its requested init segment arrives during recovery")
  void shouldPreserveFinalProducerWhenItsRequestedInitSegmentArrivesDuringRecovery() {
    var executor = new PublishingTargetsExecutor();
    var rig = rigWith(executor);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    registry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    executor.markDead(sessionId);
    rig.lifecycle().recover(sessionId, StreamSession.defaultVariant(), "init.mp4");
    var replacementAttempt = session.getHandle().orElseThrow().attemptId();
    clock.advance(properties.producerStallThreshold().plus(properties.targetSegmentDuration()));
    executor.onNextLookup = () -> store.addSegment(sessionId, "init.mp4", new byte[] {1});

    var result = rig.lifecycle().recover(sessionId, StreamSession.defaultVariant(), "init.mp4");

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(replacementAttempt);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(executor.getStoppedVariants()).isEmpty();
    assertThat(rig.coordinator().deliver(sessionId, StreamSession.defaultVariant(), "init.mp4"))
        .isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should preserve the producer when publication advances during recovery target lookup")
  void shouldPreserveProducerWhenPublicationAdvancesDuringRecoveryTargetLookup() {
    var executor = new PublishingTargetsExecutor();
    var rig = rigWith(executor);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    registry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    var originalAttempt = session.getHandle().orElseThrow().attemptId();
    rig.lifecycle().recover(sessionId, StreamSession.defaultVariant(), "init.mp4");
    clock.advance(properties.producerStallThreshold().plus(properties.targetSegmentDuration()));
    executor.onNextLookup = () -> store.addSegment(sessionId, "segment0.m4s", new byte[] {0x47});

    var result = rig.lifecycle().recover(sessionId, StreamSession.defaultVariant(), "init.mp4");

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(originalAttempt);
    assertThat(executor.getStoppedVariants()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should reset the recovery budget when a seek races an accepted or refused replacement")
  void shouldResetRecoveryBudgetWhenSeekRacesAcceptedOrRefusedReplacement(boolean refused)
      throws Exception {
    var executor = new StartGateExecutor();
    var rig = rigWith(executor);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    registry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    executor.markDead(sessionId);
    if (refused) {
      executor.refuseTarget(ExecutionTargetId.LOCAL);
    }

    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var recovery =
            threads.submit(
                () ->
                    rig.lifecycle()
                        .recover(sessionId, StreamSession.defaultVariant(), "segment0.ts"));
        await(executor.starting);
        var seeking = new CountDownLatch(1);
        var seek =
            threads.submit(
                () -> {
                  seeking.countDown();
                  rig.lifecycle().ensurePositioned(sessionId, "segment50.ts");
                });
        await(seeking);
        executor.proceed.countDown();
        recovery.get(5, TimeUnit.SECONDS);
        seek.get(5, TimeUnit.SECONDS);

        assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
        assertThat(session.getHandle().orElseThrow().startSequenceNumber()).isEqualTo(50);

        executor.acceptTarget(ExecutionTargetId.LOCAL);
        executor.markDead(sessionId);
        assertThat(
                rig.lifecycle().recover(sessionId, StreamSession.defaultVariant(), "segment50.ts"))
            .isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
        assertThat(executor.isRunning(sessionId, StreamSession.defaultVariant())).isTrue();
        store.addSegment(sessionId, "segment50.ts", new byte[] {0x47});
        assertThat(
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment50.ts"))
            .isInstanceOf(SegmentDelivery.Ready.class);
      } finally {
        executor.proceed.countDown();
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should keep the replacement healthy when two segment requests recover a dead or stalled producer")
  void shouldKeepReplacementHealthyWhenTwoSegmentRequestsRecoverDeadOrStalledProducer(
      boolean stalled) throws Exception {
    var race = new RecoveryRace(store);
    var lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(race.executor)
            .segmentStore(store)
            .properties(properties)
            .runtimeRegistry(registry)
            .sessionMutex(race.mutexFactory())
            .clock(clock)
            .build();
    var coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(store)
            .producerLifecycle(lifecycle)
            .pollInterval(Duration.ofMillis(1))
            .build();
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    registry.save(session);
    lifecycle.startAll(session, 0, 0);
    lifecycle.recover(sessionId, StreamSession.defaultVariant(), "segment0.ts");
    if (stalled) {
      clock.advance(properties.producerStallThreshold().plus(properties.targetSegmentDuration()));
    }

    if (!stalled) {
      race.executor.markDead(sessionId);
    }

    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        var first =
            threads.submit(
                () -> {
                  race.first = Thread.currentThread();
                  try {
                    return coordinator.deliver(
                        sessionId, StreamSession.defaultVariant(), "segment0.ts");
                  } finally {
                    race.firstFinished.countDown();
                  }
                });
        await(race.lookingUpTargets);
        var second =
            threads.submit(
                () -> {
                  race.second = Thread.currentThread();
                  return coordinator.deliver(
                      sessionId, StreamSession.defaultVariant(), "segment0.ts");
                });

        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
        assertThat(second.get(5, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
      } finally {
        store.addSegment(sessionId, "segment0.ts", new byte[] {0x47});
        race.continueRecovery.countDown();
        race.firstFinished.countDown();
      }
    }

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(race.executor.isRunning(sessionId, StreamSession.defaultVariant())).isTrue();
    assertThat(race.executor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
    assertThat(race.executor.getStoppedVariants()).hasSize(stalled ? 1 : 0);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).as("recovery race gate reached").isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Interrupted at recovery race gate", e);
    }
  }

  private static final class StartGateExecutor extends FakeTranscodeExecutor {
    private final CountDownLatch starting = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);

    @Override
    public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
      starting.countDown();
      await(proceed);
      return super.start(request, target);
    }
  }

  private static final class PublishingTargetsExecutor extends FakeTranscodeExecutor {
    private Runnable onNextLookup;

    @Override
    public Set<ExecutionTargetId> executionTargets() {
      var publication = onNextLookup;
      onNextLookup = null;
      if (publication != null) {
        publication.run();
      }

      return super.executionTargets();
    }
  }

  private static final class RecoveryRace {

    private final CountDownLatch lookingUpTargets = new CountDownLatch(1);
    private final CountDownLatch continueRecovery = new CountDownLatch(1);
    private final CountDownLatch firstFinished = new CountDownLatch(1);
    private final AtomicBoolean serialized = new AtomicBoolean();
    private final ReentrantLock lock = new RecoveryLock();
    private final FakeTranscodeExecutor executor;
    private volatile Thread first;
    private volatile Thread second;

    private RecoveryRace(FakeSegmentStore store) {
      executor = new RacingExecutor(store);
    }

    private MutexFactory<UUID> mutexFactory() {
      return new MutexFactory<>() {
        @Override
        public ReentrantLock getMutex(UUID sessionId) {
          return lock;
        }
      };
    }

    private final class RecoveryLock extends ReentrantLock {

      @Override
      public void lock() {
        // Permit serialization too: a waiter holding the mutex must not wait for its contender
        // to acquire that same mutex before the external target lookup can finish.
        if (Thread.currentThread() == second && isLocked()) {
          serialized.set(true);
          continueRecovery.countDown();
        }

        super.lock();
      }
    }

    private final class RacingExecutor extends FakeTranscodeExecutor {

      private final FakeSegmentStore store;
      private final AtomicBoolean pauseLookup = new AtomicBoolean(true);

      private RacingExecutor(FakeSegmentStore store) {
        this.store = store;
      }

      @Override
      public Set<ExecutionTargetId> executionTargets() {
        if (Thread.currentThread() == first && pauseLookup.compareAndSet(true, false)) {
          lookingUpTargets.countDown();
          await(continueRecovery);
        }

        return super.executionTargets();
      }

      @Override
      public boolean isRunning(UUID sessionId, String variantLabel) {
        var running = super.isRunning(sessionId, variantLabel);
        if (running && !getStartedTargets().isEmpty() && Thread.currentThread() == second) {
          continueRecovery.countDown();
          awaitUnserializedWaiter();
          store.addSegment(sessionId, "segment0.ts", new byte[] {0x47});
        }

        if (running && !getStartedTargets().isEmpty() && Thread.currentThread() == first) {
          store.addSegment(sessionId, "segment0.ts", new byte[] {0x47});
        }

        return running;
      }

      private void awaitUnserializedWaiter() {
        if (!serialized.get()) {
          await(firstFinished);
        }
      }
    }
  }
}
