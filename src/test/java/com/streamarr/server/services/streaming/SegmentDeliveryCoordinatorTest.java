package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
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
import java.util.function.BooleanSupplier;
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
  private StreamingProperties properties;
  private ProducerLifecycleService lifecycle;
  private MutableClock clock;
  private SegmentDeliveryCoordinator coordinator;

  @BeforeEach
  void setUp() {
    transcodeExecutor = new FakeTranscodeExecutor();
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
    var rig = rigWith(transcodeExecutor, segmentStore);
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
  }

  private record DeliveryRig(
      ProducerLifecycleService lifecycle, SegmentDeliveryCoordinator coordinator) {}

  private DeliveryRig rigWith(FakeTranscodeExecutor executor) {
    return rigWith(executor, segmentStore);
  }

  private DeliveryRig rigWith(FakeTranscodeExecutor executor, FakeSegmentStore store) {
    var rigLifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(executor)
            .segmentStore(store)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(new MutexFactory<>())
            .build();
    var rigCoordinator =
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(runtimeRegistry)
            .segmentStore(store)
            .transcodeExecutor(executor)
            .producerLifecycle(rigLifecycle)
            .properties(properties)
            .clock(clock)
            .pollInterval(Duration.ofMillis(20))
            .build();
    return new DeliveryRig(rigLifecycle, rigCoordinator);
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

  @Test
  @DisplayName("Should recover through execution targets when a suspended session fails to resume")
  void shouldRecoverThroughExecutionTargetsWhenSuspendedSessionFailsToResume() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    lifecycle.suspend(session);
    transcodeExecutor.failUntargetedStarts();

    var delivery = deliverAsync(sessionId, "segment1.ts");
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().contains(ExecutionTargetId.LOCAL));
    segmentStore.addSegment(sessionId, "segment1.ts", new byte[] {1});

    // A failed resume enters recovery instead of escaping as a raw server error.
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should exhaust to unrecoverable when a failed resume has no willing target")
  void shouldExhaustToUnrecoverableWhenFailedResumeHasNoWillingTarget() {
    var session = startedSession();
    lifecycle.suspend(session);
    transcodeExecutor.failUntargetedStarts();
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);

    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment1.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should not kill a healthy replacement when a lagging waiter resumes its recovery")
  void shouldNotKillHealthyReplacementWhenLaggingWaiterResumesItsRecovery() throws Exception {
    var gatingExecutor = new EvidenceGatingExecutor();
    var rig = rigWith(gatingExecutor);
    gatingExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    gatingExecutor.markDead(sessionId);

    // The lagging waiter observes the death and blocks inside evidence consumption.
    gatingExecutor.blockFirstDeathEvidence();
    var laggingWaiter =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment1.ts"));
    await().atMost(2, TimeUnit.SECONDS).until(gatingExecutor::firstDeathEvidenceBlocked);

    // A second waiter completes the full recovery: healthy replacement Y on TARGET_A.
    var promptWaiter =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment1.ts"));
    await().atMost(2, TimeUnit.SECONDS).until(() -> gatingExecutor.getStartedTargets().size() == 1);
    var attemptY = session.getHandle().attemptId();

    gatingExecutor.releaseDeathEvidence();
    Thread.sleep(300);

    // One death, one replacement: the healthy producer was neither stopped nor replaced again.
    assertThat(gatingExecutor.getStartedTargets()).containsExactly(TARGET_A);
    assertThat(gatingExecutor.getStoppedVariants()).isEmpty();
    assertThat(session.getHandle().attemptId()).isEqualTo(attemptY);

    segmentStore.addSegment(sessionId, "segment1.ts", new byte[] {1});
    assertThat(laggingWaiter.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(promptWaiter.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName("Should open a fresh cycle after a seek revival races an exhausting waiter")
  void shouldOpenFreshCycleAfterSeekRevivalRacesExhaustingWaiter() throws Exception {
    var trapStore = new TrapSegmentStore();
    var rig = rigWith(transcodeExecutor, trapStore);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);

    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);

    // The exhauster gets trapped between markExhausted (variant now FAILED) and the tail write
    // that would retain its cycle.
    var exhausterOutcome = new AtomicReference<SegmentDelivery>();
    var exhauster =
        new Thread(
            () ->
                exhausterOutcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment0.ts")),
            "exhauster");
    trapStore.armTrap(exhauster, () -> session.getHandle().status() == TranscodeStatus.FAILED);
    exhauster.start();
    assertThat(trapStore.reachedTrap.await(5, TimeUnit.SECONDS)).isTrue();

    // A planned seek revives the variant with a fresh attempt while the exhauster is trapped.
    var seekerOutcome = new AtomicReference<SegmentDelivery>();
    var seeker =
        new Thread(
            () ->
                seekerOutcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment50.ts")),
            "seeker");
    seeker.start();
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> session.getHandle().status() == TranscodeStatus.ACTIVE);
    Thread.sleep(200);
    seeker.interrupt();
    seeker.join(2000);
    assertThat(seekerOutcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);

    trapStore.releaseTrap.countDown();
    exhauster.join(2000);
    assertThat(exhausterOutcome.get()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);

    // The revived attempt dies; targets now accept. The stale exhausted cycle must not survive:
    // a fresh cycle opens and recovery replaces the producer instead of spinning forever.
    transcodeExecutor.acceptTarget(TARGET_A);
    transcodeExecutor.acceptTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);
    var targetedStartsBefore = transcodeExecutor.getStartedTargets().size();

    var recovered =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment50.ts"));
    await()
        .atMost(2, TimeUnit.SECONDS)
        .until(() -> transcodeExecutor.getStartedTargets().size() > targetedStartsBefore);
    trapStore.addSegment(sessionId, "segment50.ts", new byte[] {1});

    assertThat(recovered.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName("Should not resurrect a destroyed session when a replace races the destroy")
  void shouldNotResurrectDestroyedSessionWhenReplaceRacesTheDestroy() throws Exception {
    var gatingExecutor = new EvidenceGatingExecutor();
    var rig = rigWith(gatingExecutor);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startAll(session, 0, 0);
    var attemptX = session.getHandle().attemptId();
    gatingExecutor.markDead(sessionId);
    var streamingService =
        new HlsStreamingService(
            null,
            gatingExecutor,
            segmentStore,
            null,
            null,
            null,
            properties,
            null,
            runtimeRegistry,
            rig.lifecycle(),
            rig.coordinator());

    gatingExecutor.holdTargetedStarts();
    var replace =
        CompletableFuture.supplyAsync(
            () ->
                rig.lifecycle()
                    .replaceProducer(
                        ProducerLifecycleService.ReplaceProducerCommand.builder()
                            .sessionId(sessionId)
                            .variantLabel(StreamSession.defaultVariant())
                            .segmentName("segment1.ts")
                            .segmentIndex(1)
                            .expectedAttemptId(attemptX)
                            .target(ExecutionTargetId.LOCAL)
                            .build()));
    gatingExecutor.awaitTargetedStartEntered();

    // Destroy must serialize with the in-flight replace instead of losing to its save.
    var destroy = CompletableFuture.runAsync(() -> streamingService.destroySession(sessionId));
    Thread.sleep(200);
    gatingExecutor.releaseTargetedStarts();
    replace.get(5, TimeUnit.SECONDS);
    destroy.get(5, TimeUnit.SECONDS);

    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
    assertThat(gatingExecutor.getStopped()).contains(sessionId);
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

  /** Gates evidence consumption and targeted starts so races can be held open deterministically. */
  private static final class EvidenceGatingExecutor extends FakeTranscodeExecutor {

    private volatile CountDownLatch deathEvidenceGate;
    private final AtomicBoolean deathEvidenceGateTaken = new AtomicBoolean();
    private volatile boolean deathEvidenceBlocked;
    private volatile CountDownLatch targetedStartEntered;
    private volatile CountDownLatch targetedStartGate;

    private void blockFirstDeathEvidence() {
      deathEvidenceGate = new CountDownLatch(1);
    }

    private boolean firstDeathEvidenceBlocked() {
      return deathEvidenceBlocked;
    }

    private void releaseDeathEvidence() {
      deathEvidenceGate.countDown();
    }

    private void holdTargetedStarts() {
      targetedStartEntered = new CountDownLatch(1);
      targetedStartGate = new CountDownLatch(1);
    }

    private void awaitTargetedStartEntered() throws InterruptedException {
      assertThat(targetedStartEntered.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private void releaseTargetedStarts() {
      targetedStartGate.countDown();
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
