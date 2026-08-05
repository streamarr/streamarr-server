package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ProducerEnd;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplaceProducerCommand;
import com.streamarr.server.services.streaming.ProducerLifecycleService.ReplaceResult;
import com.streamarr.server.services.streaming.ffmpeg.LocalFfmpegProcessManager;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * TEMPORARY validation tests for PR #254/#255 review findings. Each test asserts the DEFECTIVE
 * behavior: a GREEN test CONFIRMS the finding is real; a failure means the finding is a false
 * positive. Delete this file after validation.
 */
@Tag("UnitTest")
@DisplayName("Review Findings Validation (green = defect confirmed)")
class ReviewFindingsValidationTest {

  private static final Duration STALL_THRESHOLD = Duration.ofMillis(200);
  private static final ExecutionTargetId TARGET_A = new ExecutionTargetId("worker-a");
  private static final ExecutionTargetId TARGET_B = new ExecutionTargetId("worker-b");

  @TempDir Path tempDir;

  private InstrumentedExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private StreamingProperties properties;
  private ProducerLifecycleService lifecycle;
  private MutableClock clock;
  private SegmentDeliveryCoordinator coordinator;

  @BeforeEach
  void setUp() {
    transcodeExecutor = new InstrumentedExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    clock = new MutableClock();
    properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(STALL_THRESHOLD)
            .build();
    lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(new MutexFactory<>())
            .build();
    coordinator =
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(runtimeRegistry)
            .segmentStore(segmentStore)
            .transcodeExecutor(transcodeExecutor)
            .producerLifecycle(lifecycle)
            .properties(properties)
            .clock(clock)
            .pollInterval(Duration.ofMillis(20))
            .build();
  }

  private StreamSession startedSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  private CompletableFuture<SegmentDelivery> deliverAsync(UUID sessionId, String segmentName) {
    return CompletableFuture.supplyAsync(
        () -> coordinator.deliver(sessionId, StreamSession.defaultVariant(), segmentName));
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 1 (Critical): TranscodeException from the resume path escapes deliver() -> HTTP 500,
  // bypassing the recovery cycle and its terminal-503 contract entirely.
  // ---------------------------------------------------------------------------------------------
  @Test
  @DisplayName("F1: resume-start failure propagates raw TranscodeException out of deliver()")
  void finding1ResumeStartFailureEscapesDeliver() {
    var session = startedSession();
    lifecycle.suspend(session);
    transcodeExecutor.failUntargetedStarts();

    assertThatThrownBy(
            () ->
                coordinator.deliver(
                    session.getSessionId(), StreamSession.defaultVariant(), "segment1.ts"))
        .isInstanceOf(TranscodeException.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 2 (Critical): with two execution targets, a lagging second waiter passes the replace
  // predicate against the FIRST waiter's healthy replacement, stops it, and starts a third
  // producer. One death -> two replacement starts, healthy producer killed.
  // ---------------------------------------------------------------------------------------------
  @Test
  @DisplayName("F2: second waiter kills the first waiter's healthy replacement (two targets)")
  void finding2SecondWaiterKillsHealthyReplacement() throws Exception {
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    var session = startedSession();
    var sessionId = session.getSessionId();
    var variant = StreamSession.defaultVariant();
    var attemptX = session.getHandle().attemptId();

    transcodeExecutor.markDead(sessionId);

    // Waiter B observes the death and blocks inside logProducerEnd (deathEvidence hook).
    transcodeExecutor.blockFirstDeathEvidence();
    var waiterB = deliverAsync(sessionId, "segment1.ts");
    await().atMost(2, TimeUnit.SECONDS).until(transcodeExecutor::firstDeathEvidenceBlocked);

    // Waiter A runs the full recovery: replaces X with healthy producer Y on TARGET_A.
    var waiterA = deliverAsync(sessionId, "segment1.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().size() == 1);
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> !session.getHandle().attemptId().equals(attemptX));
    // Waiter A's cycle bookkeeping after the handle swap is a few uncontended monitor ops.
    Thread.sleep(250);

    var attemptY = session.getHandle().attemptId();
    assertThat(transcodeExecutor.isRunning(sessionId, variant)).isTrue();
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();

    // Release waiter B: it reads (currentAttempt=Y, firstUnattempted=TARGET_B) and dispatches.
    transcodeExecutor.releaseDeathEvidence();
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().size() == 2);

    // The healthy replacement Y was stopped and a third producer started.
    assertThat(transcodeExecutor.getStoppedVariants()).containsExactly(sessionId + "/" + variant);
    assertThat(session.getHandle().attemptId()).isNotEqualTo(attemptY);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(TARGET_A, TARGET_B);

    segmentStore.addSegment(sessionId, "segment1.ts", new byte[] {1});
    assertThat(waiterA.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(waiterB.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 3 (Critical): destroySession removes the session outside the mutex; a concurrent
  // replaceProducer's unconditional save() re-inserts it. The destroyed session is resurrected.
  // ---------------------------------------------------------------------------------------------
  @Test
  @DisplayName("F3: destroy racing an in-flight replace resurrects the session in the registry")
  void finding3DestroyDuringReplaceResurrectsSession() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var attemptX = session.getHandle().attemptId();
    var hls =
        new HlsStreamingService(
            null,
            transcodeExecutor,
            segmentStore,
            null,
            null,
            null,
            properties,
            null,
            runtimeRegistry,
            lifecycle,
            coordinator);

    transcodeExecutor.holdTargetedStarts();
    var replace =
        CompletableFuture.supplyAsync(
            () ->
                lifecycle.replaceProducer(
                    ReplaceProducerCommand.builder()
                        .sessionId(sessionId)
                        .variantLabel(StreamSession.defaultVariant())
                        .segmentName("segment1.ts")
                        .segmentIndex(1)
                        .expectedAttemptId(attemptX)
                        .target(ExecutionTargetId.LOCAL)
                        .build()));
    transcodeExecutor.awaitTargetedStartEntered();

    var destroy = CompletableFuture.runAsync(() -> hls.destroySession(sessionId));
    await().atMost(2, TimeUnit.SECONDS).until(() -> runtimeRegistry.findById(sessionId).isEmpty());

    transcodeExecutor.releaseTargetedStarts();
    var result = replace.get(5, TimeUnit.SECONDS);
    destroy.get(5, TimeUnit.SECONDS);

    assertThat(result).isInstanceOf(ReplaceResult.Replaced.class);
    // destroySession completed, yet the session is back in the registry: a zombie.
    assertThat(runtimeRegistry.findById(sessionId)).isPresent();
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 4 (Important): cold-start shares the steady-state stall budget. A healthy producer
  // that is merely slow to publish its FIRST segment is stopped, its equally-slow replacement is
  // stopped too, and the variant terminally FAILs with a 503.
  // ---------------------------------------------------------------------------------------------
  @Test
  @DisplayName("F4: healthy-but-slow cold start is killed twice then surfaces Unrecoverable")
  void finding4HealthySlowColdStartEndsUnrecoverable() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();

    var delivery = deliverAsync(sessionId, "segment0.ts");
    Thread.sleep(100);
    clock.advance(STALL_THRESHOLD.plusMillis(50));

    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().size() == 1);
    // The healthy producer was stopped purely for cold-start slowness.
    assertThat(transcodeExecutor.getStoppedVariants()).hasSize(1);

    Thread.sleep(100);
    clock.advance(STALL_THRESHOLD.plusMillis(50));

    var outcome = delivery.get(5, TimeUnit.SECONDS);
    assertThat(outcome).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(transcodeExecutor.getStoppedVariants()).hasSize(2);
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 5 (Important): retainedExits is never purged by stopProcess(sessionId) — observed
  // death evidence survives session destroy and leaks for the JVM lifetime.
  // ---------------------------------------------------------------------------------------------
  @Test
  @DisplayName("F5: watched-death exit evidence survives session stop (leak)")
  void finding5RetainedExitEvidenceSurvivesSessionStop() throws Exception {
    var manager = new LocalFfmpegProcessManager();
    var sessionId = UUID.randomUUID();
    var attemptId = UUID.randomUUID();
    var process =
        manager.startProcess(
            sessionId,
            StreamSession.defaultVariant(),
            attemptId,
            List.of("bash", "-c", "echo 'boom' >&2; exit 1"),
            tempDir);
    process.waitFor();
    await().pollDelay(Duration.ofMillis(200)).until(() -> true);
    // Death observed while watched -> evidence retained.
    assertThat(manager.isRunning(sessionId, StreamSession.defaultVariant())).isFalse();

    manager.stopProcess(sessionId);

    assertThat(manager.consumeExit(sessionId, StreamSession.defaultVariant(), attemptId))
        .isPresent();
  }

  // ---------------------------------------------------------------------------------------------
  // Finding 6 (Important): a death nobody observes (no isRunning call before stopProcess) retains
  // no exit evidence at all — the crash detail is silently destroyed.
  // ---------------------------------------------------------------------------------------------
  @Test
  @DisplayName("F6: unwatched death leaves no exit evidence after disposal")
  void finding6UnwatchedDeathLeavesNoEvidence() throws Exception {
    var manager = new LocalFfmpegProcessManager();
    var sessionId = UUID.randomUUID();
    var attemptId = UUID.randomUUID();
    var process =
        manager.startProcess(
            sessionId,
            StreamSession.defaultVariant(),
            attemptId,
            List.of("bash", "-c", "echo 'crash detail' >&2; exit 1"),
            tempDir);
    process.waitFor();
    await().pollDelay(Duration.ofMillis(200)).until(() -> true);

    // Nobody calls isRunning (player closed); the reaper/destroy path disposes of the corpse.
    manager.stopProcess(sessionId);

    assertThat(manager.consumeExit(sessionId, StreamSession.defaultVariant(), attemptId)).isEmpty();
  }

  private static final class InstrumentedExecutor extends FakeTranscodeExecutor {
    private volatile boolean failUntargetedStarts;
    private volatile CountDownLatch deathEvidenceGate;
    private final AtomicBoolean deathEvidenceGateTaken = new AtomicBoolean();
    private volatile boolean deathEvidenceBlocked;
    private volatile CountDownLatch targetedStartEntered;
    private volatile CountDownLatch targetedStartGate;

    void failUntargetedStarts() {
      failUntargetedStarts = true;
    }

    void blockFirstDeathEvidence() {
      deathEvidenceGate = new CountDownLatch(1);
    }

    boolean firstDeathEvidenceBlocked() {
      return deathEvidenceBlocked;
    }

    void releaseDeathEvidence() {
      deathEvidenceGate.countDown();
    }

    void holdTargetedStarts() {
      targetedStartEntered = new CountDownLatch(1);
      targetedStartGate = new CountDownLatch(1);
    }

    void awaitTargetedStartEntered() throws InterruptedException {
      assertThat(targetedStartEntered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    void releaseTargetedStarts() {
      targetedStartGate.countDown();
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      if (failUntargetedStarts) {
        throw new TranscodeException("No connected transcode worker can run this variant");
      }
      return super.start(request);
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request, ExecutionTargetId target) {
      var entered = targetedStartEntered;
      if (entered != null) {
        entered.countDown();
        awaitQuietly(targetedStartGate);
      }
      return super.start(request, target);
    }

    @Override
    public Optional<ProducerEnd> deathEvidence(
        UUID sessionId, String variantLabel, UUID expectedAttemptId) {
      var gate = deathEvidenceGate;
      if (gate != null && deathEvidenceGateTaken.compareAndSet(false, true)) {
        deathEvidenceBlocked = true;
        awaitQuietly(gate);
      }
      return super.deathEvidence(sessionId, variantLabel, expectedAttemptId);
    }

    private static void awaitQuietly(CountDownLatch latch) {
      try {
        latch.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now =
        new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));

    void advance(Duration duration) {
      now.updateAndGet(instant -> instant.plus(duration));
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
