package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ProducerEnd;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Segment Delivery Coordinator Tests")
class SegmentDeliveryCoordinatorTest {

  private static final Duration STALL_THRESHOLD = Duration.ofMillis(200);
  private static final ExecutionTargetId TARGET_A = new ExecutionTargetId("worker-a");
  private static final ExecutionTargetId TARGET_B = new ExecutionTargetId("worker-b");
  private static final ExecutionTargetId TARGET_C = new ExecutionTargetId("worker-c");

  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;
  private MutableClock clock;
  private SegmentDeliveryCoordinator coordinator;

  @BeforeEach
  void setUp() {
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    clock = new MutableClock();
    var properties =
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

  private StreamSession startedAbrSession() {
    var session =
        defaultSessionBuilder()
            .variants(
                List.of(
                    defaultVariantBuilder()
                        .width(1920)
                        .height(1080)
                        .videoBitrate(5_000_000L)
                        .label("1080p")
                        .build(),
                    defaultVariantBuilder()
                        .width(1280)
                        .height(720)
                        .videoBitrate(3_000_000L)
                        .label("720p")
                        .build()))
            .build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  private CompletableFuture<SegmentDelivery> deliverAsync(UUID sessionId, String segmentName) {
    return deliverAsync(sessionId, StreamSession.defaultVariant(), segmentName);
  }

  private CompletableFuture<SegmentDelivery> deliverAsync(
      UUID sessionId, String variantLabel, String segmentName) {
    return CompletableFuture.supplyAsync(
        () -> coordinator.deliver(sessionId, variantLabel, segmentName));
  }

  @Test
  @DisplayName("Should serve a segment the moment it exists without waiting for its successor")
  void shouldServeSegmentTheMomentItExistsWithoutWaitingForItsSuccessor() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {0x47});

    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(((SegmentDelivery.Ready) delivery).data()).containsExactly(0x47);
  }

  @Test
  @DisplayName("Should return session ended when the session does not exist")
  void shouldReturnSessionEndedWhenTheSessionDoesNotExist() {
    var delivery =
        coordinator.deliver(UUID.randomUUID(), StreamSession.defaultVariant(), "segment0.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName("Should keep waiting without replacement while the producer is alive")
  void shouldKeepWaitingWithoutReplacementWhileTheProducerIsAlive() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment1.ts");
    // Several poll cycles pass with no publication; the frozen clock means no stall is declared.
    Thread.sleep(150);
    assertThat(delivery).isNotDone();
    segmentStore.addSegment(session.getSessionId(), "segment1.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName("Should replace a dead producer at the requested segment's offset")
  void shouldReplaceDeadProducerAtTheRequestedSegmentsOffset() throws Exception {
    var session = startedSession();
    transcodeExecutor.markDead(session.getSessionId());
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment2.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    var replacement = transcodeExecutor.getStartedRequests().getLast();
    assertThat(replacement.seekPosition()).isEqualTo(12);
    assertThat(replacement.startSequenceNumber()).isEqualTo(2);
    assertThat(replacement.variantLabel()).isEqualTo(StreamSession.defaultVariant());
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getHandle().attemptId()).isEqualTo(replacement.attemptId());
  }

  @Test
  @DisplayName("Should replace only the dead variant while its siblings keep running")
  void shouldReplaceOnlyTheDeadVariantWhileItsSiblingsKeepRunning() throws Exception {
    var session = startedAbrSession();
    transcodeExecutor.markDead(session.getSessionId(), "1080p");
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "1080p", "1080p/segment0.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "1080p/segment0.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    var replacement = transcodeExecutor.getStartedRequests().getLast();
    assertThat(replacement.variantLabel()).isEqualTo("1080p");
    assertThat(replacement.width()).isEqualTo(1920);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), "720p")).isTrue();
    assertThat(session.getVariantHandle("720p").status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should stop then replace a producer that is alive but stalled")
  void shouldStopThenReplaceProducerThatIsAliveButStalled() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment0.ts");
    Thread.sleep(80);
    clock.advance(STALL_THRESHOLD.plusMillis(50));
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStoppedVariants())
        .contains(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName("Should reset the stall clock when the producer publishes earlier segments")
  void shouldResetTheStallClockWhenTheProducerPublishesEarlierSegments() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment2.ts");
    Thread.sleep(80);
    // Progress at the frontier arrives just before each stall verdict; no replacement happens.
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});
    Thread.sleep(60);
    clock.advance(STALL_THRESHOLD.minusMillis(50));
    Thread.sleep(60);
    segmentStore.addSegment(session.getSessionId(), "segment1.ts", new byte[] {1});
    Thread.sleep(60);
    clock.advance(STALL_THRESHOLD.minusMillis(50));
    Thread.sleep(60);
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName("Should resume a session suspended mid-wait instead of classifying it as dead")
  void shouldResumeSessionSuspendedMidWaitInsteadOfClassifyingItAsDead() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment2.ts");
    Thread.sleep(80);
    lifecycle.suspend(session);
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    // A planned suspension resumes through positioning; nothing is ever marked FAILED.
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
  }

  @Test
  @DisplayName("Should consume death evidence only for the attempt it belongs to")
  void shouldConsumeDeathEvidenceOnlyForTheAttemptItBelongsTo() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var deadAttempt = session.getHandle().attemptId();
    transcodeExecutor.recordEvidence(
        sessionId,
        StreamSession.defaultVariant(),
        ProducerEnd.builder()
            .attemptId(deadAttempt)
            .kind(ProducerEnd.EndKind.PROCESS_EXIT)
            .detail("exit code 137")
            .at(Instant.now())
            .build());
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sessionId, "segment0.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    assertThat(transcodeExecutor.hasUnconsumedEvidence(sessionId, StreamSession.defaultVariant()))
        .isFalse();

    // A late end from an attempt nobody tracks anymore is never attributed to the replacement.
    transcodeExecutor.recordEvidence(
        sessionId,
        StreamSession.defaultVariant(),
        ProducerEnd.builder()
            .attemptId(UUID.randomUUID())
            .kind(ProducerEnd.EndKind.STOPPED)
            .detail("stale")
            .at(Instant.now())
            .build());
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.hasUnconsumedEvidence(sessionId, StreamSession.defaultVariant()))
        .isTrue();
  }

  @Test
  @DisplayName("Should try each snapshotted target at most once when replacements keep dying")
  void shouldTryEachSnapshottedTargetAtMostOnceWhenReplacementsKeepDying() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.markDead(sessionId);

    var delivery = deliverAsync(sessionId, "segment0.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().size() == 1);
    // The first replacement dies before publishing anything: the cycle continues, A is not
    // retried, and target B is next.
    transcodeExecutor.markDead(sessionId);
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().size() == 2);
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(TARGET_A, TARGET_B);
  }

  @Test
  @DisplayName("Should return unrecoverable and mark the variant failed when every target refuses")
  void shouldReturnUnrecoverableAndMarkTheVariantFailedWhenEveryTargetRefuses() {
    var session = startedSession();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(session.getSessionId());

    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.ts");

    // An immediate startup refusal consumes the target and exhausts to 503, never a 500.
    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should answer subsequent same-window requests with unrecoverable after exhaustion")
  void shouldAnswerSubsequentSameWindowRequestsWithUnrecoverableAfterExhaustion() {
    var session = startedSession();
    exhaustRecovery(session);

    var retry =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.ts");
    var drifted =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment3.ts");

    assertThat(retry).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(drifted).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should open a fresh cycle when a target outside the exhausted snapshot appears")
  void shouldOpenFreshCycleWhenTargetOutsideTheExhaustedSnapshotAppears() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    exhaustRecovery(session);
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B, TARGET_C));

    var delivery = deliverAsync(sessionId, "segment0.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().contains(TARGET_C));
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should treat a relocation-distance request as a planned seek restart after failure")
  void shouldTreatRelocationDistanceRequestAsPlannedSeekRestartAfterFailure() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    exhaustRecovery(session);
    var targetedStartsBefore = transcodeExecutor.getStartedTargets().size();

    var delivery = deliverAsync(sessionId, "segment50.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> session.getHandle().status() == TranscodeStatus.ACTIVE);
    segmentStore.addSegment(sessionId, "segment50.ts", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    // The seek revived the variant through positioning — a planned restart, not a recovery cycle.
    assertThat(transcodeExecutor.getStartedTargets()).hasSize(targetedStartsBefore);
    var revival = transcodeExecutor.getStartedRequests().getLast();
    assertThat(revival.startSequenceNumber()).isEqualTo(50);
    assertThat(revival.seekPosition()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should replace once then exhaust when runs complete without the advertised segment")
  void shouldReplaceOnceThenExhaustWhenRunsCompleteWithoutTheAdvertisedSegment() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.recordEvidence(
        sessionId,
        StreamSession.defaultVariant(),
        ProducerEnd.builder()
            .attemptId(session.getHandle().attemptId())
            .kind(ProducerEnd.EndKind.COMPLETED)
            .detail("completed")
            .at(Instant.now())
            .build());
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sessionId, "segment2.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    // The replacement also completes without producing the advertised segment.
    transcodeExecutor.markDead(sessionId);

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should start exactly one replacement when two waiters observe one death")
  void shouldStartExactlyOneReplacementWhenTwoWaitersObserveOneDeath() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var first = deliverAsync(sessionId, "segment0.ts");
    var second = deliverAsync(sessionId, "segment0.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    Thread.sleep(100);
    segmentStore.addSegment(sessionId, "segment0.ts", new byte[] {1});

    assertThat(first.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(second.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
  }

  @Test
  @DisplayName("Should end the wait promptly when the session is destroyed")
  void shouldEndTheWaitPromptlyWhenTheSessionIsDestroyed() throws Exception {
    var session = startedSession();

    var delivery = deliverAsync(session.getSessionId(), "segment1.ts");
    Thread.sleep(60);
    runtimeRegistry.removeById(session.getSessionId());

    assertThat(delivery.get(1, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName("Should return cancelled and restore the interrupt without touching the producer")
  void shouldReturnCancelledAndRestoreTheInterruptWithoutTouchingTheProducer() throws Exception {
    var session = startedSession();
    var outcome = new AtomicReference<SegmentDelivery>();
    var interruptRestored = new AtomicBoolean();
    var waiter =
        new Thread(
            () -> {
              outcome.set(
                  coordinator.deliver(
                      session.getSessionId(), StreamSession.defaultVariant(), "segment1.ts"));
              interruptRestored.set(Thread.currentThread().isInterrupted());
            });
    waiter.start();
    Thread.sleep(80);

    waiter.interrupt();
    waiter.join(2000);

    assertThat(outcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);
    assertThat(interruptRestored).isTrue();
    assertThat(transcodeExecutor.getStopped()).isEmpty();
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
  }

  @Test
  @DisplayName("Should map an init segment request to the current run's start sequence number")
  void shouldMapInitSegmentRequestToTheCurrentRunsStartSequenceNumber() throws Exception {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 12, 2);
    transcodeExecutor.markDead(session.getSessionId());
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "init.mp4");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedRequests().size() == startsBefore + 1);
    segmentStore.addSegment(session.getSessionId(), "init.mp4", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    var replacement = transcodeExecutor.getStartedRequests().getLast();
    assertThat(replacement.startSequenceNumber()).isEqualTo(2);
    assertThat(replacement.seekPosition()).isEqualTo(12);
  }

  private void exhaustRecovery(StreamSession session) {
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(session.getSessionId());
    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.ts");
    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  /** Frozen unless advanced: stall verdicts fire only when a test says time has passed. */
  private static final class MutableClock extends Clock {

    private volatile Instant instant = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration duration) {
      instant = instant.plus(duration);
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
      return instant;
    }
  }
}
