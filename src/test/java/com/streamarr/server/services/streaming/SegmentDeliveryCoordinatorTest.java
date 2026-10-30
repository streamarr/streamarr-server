package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.SegmentRequestFixture.earliestArrivingSegmentRequestBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultLadderSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.sessionWithDurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.StreamingRigFixture.StreamingRig;
import com.streamarr.server.services.concurrency.MutexFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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

  private StreamingRig rigWith(FakeTranscodeExecutor executor) {
    return rigWith(executor, segmentStore);
  }

  private StreamingRig rigWith(FakeTranscodeExecutor executor, FakeSegmentStore store) {
    return rigBuilder(executor, store).build();
  }

  private StreamingRigFixture.StreamingRigBuilder rigBuilder(
      FakeTranscodeExecutor executor, FakeSegmentStore store) {
    return StreamingRigFixture.streamingRigBuilder()
        .transcodeExecutor(executor)
        .segmentStore(store)
        .properties(properties)
        .runtimeRegistry(runtimeRegistry)
        .clock(clock)
        .heldRequests(executor.heldSegmentRequests());
  }

  // Stores the segment, then wakes the requests held on its variant, as a worker's upload does.
  private void upload(UUID sessionId, String segmentName, byte[] data) {
    segmentStore.addSegment(sessionId, segmentName, data);
    heldRequests().wakeVariant(sessionId, variantOf(segmentName));
  }

  private static String variantOf(String segmentName) {
    var slash = segmentName.indexOf('/');
    if (slash < 0) {
      return StreamSession.defaultVariant();
    }

    return segmentName.substring(0, slash);
  }

  // Advances the clock, then wakes the requests held on the default variant so that they look
  // again at the new time, as the end of their wait at the stall deadline does.
  private void advanceClockThenWake(UUID sessionId, Duration duration) {
    clock.advance(duration);
    heldRequests().wakeVariant(sessionId, StreamSession.defaultVariant());
  }

  // Wakes the requests held on the default variant, then waits until one has looked again. A
  // request looks again only after its previous pass has returned.
  private void awaitNextLook(UUID sessionId) {
    awaitNextLook(sessionId, StreamSession.defaultVariant());
  }

  private void awaitNextLook(UUID sessionId, String variantLabel) {
    var looks = transcodeExecutor.livenessChecks();
    heldRequests().wakeVariant(sessionId, variantLabel);
    transcodeExecutor.awaitLivenessCheckCount(looks + 1);
  }

  // Uploads an earlier segment, then waits until the held request has looked at it as progress.
  private void uploadProgress(UUID sessionId, String segmentName) {
    var looks = transcodeExecutor.livenessChecks();
    upload(sessionId, segmentName, new byte[] {1});
    transcodeExecutor.awaitLivenessCheckCount(looks + 1);
  }

  // Destroys the session as the service does. Its removal waits for the session mutex, so a
  // replacement in flight cannot save the destroyed session back into the registry.
  private void destroy(UUID sessionId) {
    HlsStreamingService.builder()
        .meterRegistry(new SimpleMeterRegistry())
        .segmentStore(segmentStore)
        .runtimeRegistry(runtimeRegistry)
        .producerLifecycle(lifecycle)
        .deliveryCoordinator(coordinator)
        .build()
        .destroySession(sessionId);
  }

  private HeldSegmentRequests heldRequests() {
    return transcodeExecutor.heldSegmentRequests();
  }

  private StreamSession startedSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
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

  // Delivers on another thread and returns once the request has looked at its variant and found
  // no segment, so that it is held until a wake or its stall deadline.
  private CompletableFuture<SegmentDelivery> heldDelivery(UUID sessionId, String segmentName) {
    return heldDelivery(sessionId, StreamSession.defaultVariant(), segmentName);
  }

  private CompletableFuture<SegmentDelivery> heldDelivery(
      UUID sessionId, String variantLabel, String segmentName) {
    var looks = transcodeExecutor.livenessChecks(sessionId, variantLabel);
    var delivery = deliverAsync(sessionId, variantLabel, segmentName);
    transcodeExecutor.awaitLivenessCheckCount(sessionId, variantLabel, looks + 1);
    return delivery;
  }

  @Test
  @DisplayName(
      "Should serve a segment the moment it exists without waiting for its successor when delivering a segment")
  void shouldServeSegmentTheMomentItExistsWithoutWaitingForItsSuccessorWhenDeliveringSegment() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment0.m4s", new byte[] {0x47});

    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(((SegmentDelivery.Ready) delivery).data()).containsExactly(0x47);
  }

  @Test
  @DisplayName("Should return session ended when the session does not exist")
  void shouldReturnSessionEndedWhenTheSessionDoesNotExist() {
    var delivery =
        coordinator.deliver(UUID.randomUUID(), StreamSession.defaultVariant(), "segment0.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName("Should hold no request when a request reaches a destroyed session")
  void shouldHoldNoRequestWhenARequestReachesADestroyedSession() {
    var meterRegistry = new SimpleMeterRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .clock(clock)
            .heldRequests(new HeldSegmentRequests(meterRegistry))
            .build();
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    rig.lifecycle().startFirstListedVariant(session);
    runtimeRegistry.removeById(session.getSessionId());
    rig.coordinator().forgetSession(session.getSessionId());

    var delivery =
        rig.coordinator()
            .deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment1.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
    assertThat(meterRegistry.get("streamarr.streaming.held_segment_requests").gauge().value())
        .isZero();
  }

  @Test
  @DisplayName("Should return session ended when the variant is unknown on a live session")
  void shouldReturnSessionEndedWhenTheVariantIsUnknownOnALiveSession() {
    var session = startedSession();

    var delivery = coordinator.deliver(session.getSessionId(), "1080p", "1080p/segment0.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should return session ended without entering recovery when destruction follows the initial session lookup")
  void shouldReturnSessionEndedWithoutEnteringRecoveryWhenDestructionFollowsInitialSessionLookup()
      throws Exception {
    var destroyRaceRegistry = new DestroyAfterLookupRegistry();
    runtimeRegistry = destroyRaceRegistry;
    var rig = rigWith(transcodeExecutor, segmentStore);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startFirstListedVariant(session);
    destroyRaceRegistry.destroyAfterNextLookup(
        () -> {
          runtimeRegistry.removeById(sessionId);
          rig.coordinator().forgetSession(sessionId);
        });

    var outcome = new AtomicReference<SegmentDelivery>();
    var delivery =
        Thread.ofVirtual()
            .name("delivery-destroy-race")
            .start(
                () ->
                    outcome.set(
                        rig.coordinator()
                            .deliver(sessionId, StreamSession.defaultVariant(), "segment0.m4s")));

    try {
      delivery.join(2000);

      assertThat(delivery.isAlive()).isFalse();
      assertThat(outcome.get()).isInstanceOf(SegmentDelivery.SessionEnded.class);
    } finally {
      delivery.interrupt();
      delivery.join(2000);
    }
  }

  @Test
  @DisplayName(
      "Should serve a last-gasp publication that races the exhaustion when delivering a segment")
  void shouldServeALastGaspPublicationThatRacesTheExhaustionWhenDeliveringSegment()
      throws Exception {
    var trapStore = new TrapSegmentStore();
    var rig = rigWith(transcodeExecutor, trapStore);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startFirstListedVariant(session);
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);

    // Trap the exhauster between markExhausted (variant now FAILED) and its terminal check; a
    // publication landing in that window must be served, never reported unrecoverable.
    var outcome = new AtomicReference<SegmentDelivery>();
    var exhauster =
        new Thread(
            () ->
                outcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment0.m4s")),
            "last-gasp-exhauster");
    trapStore.armTrap(
        exhauster, () -> session.getHandle().orElseThrow().status() == TranscodeStatus.FAILED);
    exhauster.start();
    assertThat(trapStore.reachedTrap.await(5, TimeUnit.SECONDS)).isTrue();

    trapStore.addSegment(sessionId, "segment0.m4s", new byte[] {0x47});
    trapStore.releaseTrap.countDown();
    exhauster.join(5000);

    assertThat(outcome.get()).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(((SegmentDelivery.Ready) outcome.get()).data()).containsExactly(0x47);
  }

  @Test
  @DisplayName(
      "Should reject a segment name matching no naming scheme without disturbing the producer when delivering a segment")
  void
      shouldRejectSegmentNameMatchingNoNamingSchemeWithoutDisturbingTheProducerWhenDeliveringSegment()
          throws Exception {
    var session = startedSession();

    var delivery = deliverAsync(session.getSessionId(), "foo.m4s");

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should reject a media segment past the advertised timeline without disturbing the producer when delivering a segment")
  void
      shouldRejectMediaSegmentPastTheAdvertisedTimelineWithoutDisturbingTheProducerWhenDeliveringSegment()
          throws Exception {
    var sixtySecondSession = sessionWithDurationBuilder(60).build();
    runtimeRegistry.save(sixtySecondSession);
    lifecycle.startFirstListedVariant(sixtySecondSession);
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sixtySecondSession.getSessionId(), "segment10.m4s");

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
    assertThat(transcodeExecutor.getStopped()).isEmpty();
    assertThat(
            transcodeExecutor.isRunning(
                sixtySecondSession.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should answer a stored media segment past the advertised timeline as not found when delivering a segment")
  void shouldAnswerStoredMediaSegmentPastTheAdvertisedTimelineAsNotFoundWhenDeliveringSegment()
      throws Exception {
    var sixtySecondSession = sessionWithDurationBuilder(60).build();
    runtimeRegistry.save(sixtySecondSession);
    lifecycle.startFirstListedVariant(sixtySecondSession);
    segmentStore.addSegment(sixtySecondSession.getSessionId(), "segment10.m4s", new byte[] {10});

    var delivery = deliverAsync(sixtySecondSession.getSessionId(), "segment10.m4s");

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName("Should report the session as ended when a destroy wins between existence and read")
  void shouldReportSessionEndedWhenADestroyWinsBetweenExistenceAndRead() {
    var throwingStore =
        new FakeSegmentStore() {
          @Override
          public byte[] readSegment(UUID sessionId, String segmentName) {
            throw new TranscodeException("Segment not found: " + segmentName);
          }
        };
    var sessionId = UUID.randomUUID();
    throwingStore.addSegment(sessionId, "segment0.m4s", new byte[] {0x47});
    var rig = rigWith(transcodeExecutor, throwingStore);

    var delivery =
        rig.coordinator().deliver(sessionId, StreamSession.defaultVariant(), "segment0.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName(
      "Should reject a segment index that overflows the naming scheme when delivering a segment")
  void shouldRejectSegmentIndexThatOverflowsTheNamingSchemeWhenDeliveringSegment() {
    var session = startedSession();

    var delivery =
        coordinator.deliver(
            session.getSessionId(),
            StreamSession.defaultVariant(),
            "segment99999999999999999999.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName(
      "Should keep waiting without replacement while the producer is alive when delivering a segment")
  void shouldKeepWaitingWithoutReplacementWhileTheProducerIsAliveWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    // The request looks at its live producer, finds no segment, and waits for a wake; the frozen
    // clock means no stall is declared.
    var delivery = heldDelivery(session.getSessionId(), "segment1.m4s");
    assertThat(delivery).isNotDone();
    upload(session.getSessionId(), "segment1.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName(
      "Should fail a held request at once when its producer dies and no target can replace it")
  void shouldFailHeldRequestAtOnceWhenItsProducerDiesAndNoTargetCanReplaceIt() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A));
    transcodeExecutor.refuseTarget(TARGET_A);

    // The frozen clock keeps the producer 6.2 s of real time away from its startup stall
    // deadline, so only the death's wake can end the wait inside the 2 s bound below.
    var delivery = heldDelivery(sessionId, "segment1.m4s");
    transcodeExecutor.markDead(sessionId);

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should replace a held request's producer at once when the producer dies")
  void shouldReplaceHeldRequestsProducerAtOnceWhenTheProducerDies() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = heldDelivery(sessionId, "segment1.m4s");
    transcodeExecutor.markDead(sessionId);
    // The replacement must start before the 6.2 s startup stall deadline could end the wait.
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(sessionId, "segment1.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests().getLast().startSequenceNumber()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should replace a stalled producer at its stall deadline when nothing wakes the held request")
  void shouldReplaceStalledProducerAtItsStallDeadlineWhenNothingWakesTheHeldRequest()
      throws Exception {
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .heldRequests(heldRequests())
            .build();
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startFirstListedVariant(session);
    var startsBefore = transcodeExecutor.getStartedRequests().size();
    // A stored segment 0 takes the producer out of startup, so its 200 ms stall deadline runs on
    // the system clock from the request's first look.
    segmentStore.addSegment(sessionId, "segment0.m4s", new byte[] {1});

    var delivery =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment1.m4s"));
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(sessionId, "segment1.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStoppedVariants())
        .containsExactly(sessionId + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName(
      "Should replace a dead producer at the requested segment's offset when delivering a segment")
  void shouldReplaceDeadProducerAtTheRequestedSegmentsOffsetWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    transcodeExecutor.markDead(session.getSessionId());
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "segment2.m4s");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(session.getSessionId(), "segment2.m4s", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    var replacement = transcodeExecutor.getStartedRequests().getLast();
    assertThat(replacement.seekPosition()).isEqualTo(12);
    assertThat(replacement.startSequenceNumber()).isEqualTo(2);
    assertThat(replacement.variantLabel()).isEqualTo(StreamSession.defaultVariant());
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(replacement.attemptId());
  }

  @Test
  @DisplayName("Should replace a dead variant with its own geometry when delivering a segment")
  void shouldReplaceADeadVariantWithItsOwnGeometryWhenDeliveringASegment() throws Exception {
    var session = startedLadderSession();
    transcodeExecutor.markDead(session.getSessionId(), "480p");
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "480p", "480p/segment0.m4s");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(session.getSessionId(), "480p/segment0.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startsBefore))
        .singleElement()
        .satisfies(
            replacement -> {
              assertThat(replacement.variantLabel()).isEqualTo("480p");
              assertThat(replacement.width()).isEqualTo(854);
            });
    assertThat(session.getVariantHandle("480p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName(
      "Should stop then replace a producer that is alive but stalled when delivering a segment")
  void shouldStopThenReplaceProducerThatIsAliveButStalledWhenDeliveringSegment() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = heldDelivery(session.getSessionId(), "segment1.m4s");
    // Publishing segment0 takes the run out of startup, so the stall budget that follows is the
    // steady-state threshold measured from a real publication.
    uploadProgress(session.getSessionId(), "segment0.m4s");
    advanceClockThenWake(session.getSessionId(), STALL_THRESHOLD.plusMillis(50));
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(session.getSessionId(), "segment1.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStoppedVariants())
        .contains(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName("Should replace a producer at the exact stall threshold when delivering a segment")
  void shouldReplaceAProducerAtTheExactStallThresholdWhenDeliveringSegment() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = heldDelivery(session.getSessionId(), "segment1.m4s");
    uploadProgress(session.getSessionId(), "segment0.m4s");
    advanceClockThenWake(session.getSessionId(), STALL_THRESHOLD);
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(session.getSessionId(), "segment1.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStoppedVariants())
        .contains(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName(
      "Should not replace a cold-starting producer that is still within its startup budget when delivering a segment")
  void shouldNotReplaceColdStartingProducerWithinItsStartupBudgetWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = heldDelivery(session.getSessionId(), "segment0.m4s");
    // A run that has published nothing yet has to encode a whole segment before it can; the
    // steady-state threshold alone would kill a healthy encoder and restart it identically.
    var looks = transcodeExecutor.livenessChecks();
    advanceClockThenWake(session.getSessionId(), STALL_THRESHOLD.plusMillis(50));
    transcodeExecutor.awaitLivenessCheckCount(looks + 1);

    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
    // End the wait deterministically: cancel(true) cannot interrupt the supplier thread, and an
    // abandoned deliver would keep waiting for the rest of the JVM.
    destroy(session.getSessionId());
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName(
      "Should observe the run's own output as progress for a request at its start index when delivering a segment")
  void shouldObserveRunsOwnOutputAsProgressForRequestAtItsStartIndexWhenDeliveringSegment()
      throws Exception {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    session.setCurrentSegment(900);
    lifecycle.startFirstListedVariant(session);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    // init.mp4 resolves to the run's start index, so no earlier segment can ever exist -- the
    // producer's own output is the only progress this request can observe.
    var delivery = heldDelivery(session.getSessionId(), "init.mp4");
    uploadProgress(session.getSessionId(), "segment900.m4s");
    // Past the steady-state threshold but far inside the startup budget. A replacement here can
    // only mean the published sibling was counted, taking the run out of startup.
    advanceClockThenWake(session.getSessionId(), STALL_THRESHOLD.plusMillis(50));

    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    assertThat(transcodeExecutor.getStoppedVariants()).hasSize(1);
    // End the wait deterministically: cancel(true) cannot interrupt the supplier thread, and an
    // abandoned deliver would keep waiting for the rest of the JVM.
    destroy(session.getSessionId());
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName("Should reset the stall clock when the producer publishes earlier segments")
  void shouldResetTheStallClockWhenTheProducerPublishesEarlierSegments() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = heldDelivery(session.getSessionId(), "segment2.m4s");
    // Each earlier segment is published, then the held request looks at it, which advances the
    // frontier and resets the stall clock, before time advances by a sub-threshold gap. The reset
    // keeps those gaps from ever accumulating into a stall, so no replacement happens.
    uploadProgress(session.getSessionId(), "segment0.m4s");
    advanceClockThenWake(session.getSessionId(), STALL_THRESHOLD.minusMillis(50));
    uploadProgress(session.getSessionId(), "segment1.m4s");
    advanceClockThenWake(session.getSessionId(), STALL_THRESHOLD.minusMillis(50));
    upload(session.getSessionId(), "segment2.m4s", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName(
      "Should resume a session suspended mid-wait instead of classifying it as dead when delivering a segment")
  void shouldResumeSessionSuspendedMidWaitInsteadOfClassifyingItAsDeadWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    // Let the delivery reach its wait loop, then suspend the session out from under it.
    var delivery = heldDelivery(session.getSessionId(), "segment2.m4s");
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(session.getSessionId(), "segment2.m4s", new byte[] {2});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    // A planned suspension resumes through positioning; nothing is ever marked FAILED.
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
  }

  @Test
  @DisplayName("Should try each eligible target at most once when replacements keep dying")
  void shouldTryEachEligibleTargetAtMostOnceWhenReplacementsKeepDying() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.markDead(sessionId);

    var delivery = deliverAsync(sessionId, "segment0.m4s");
    // The fake records a start once its job runs, so the replacement that ends next is target A's.
    transcodeExecutor.awaitStartedTargetCount(1);
    // The first replacement dies before publishing anything: recovery continues, A is not
    // retried, and target B is next.
    transcodeExecutor.markDead(sessionId);
    transcodeExecutor.awaitStartedTargetCount(2);
    upload(sessionId, "segment0.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(TARGET_A, TARGET_B);
  }

  @Test
  @DisplayName(
      "Should retry the same target after a replacement publishes progress and dies again when delivering a segment")
  void shouldRetrySameTargetAfterReplacementPublishesProgressAndDiesAgainWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A));
    transcodeExecutor.markDead(sessionId);

    var firstDelivery = deliverAsync(sessionId, "segment0.m4s");
    transcodeExecutor.awaitStartedTargetCount(1);
    upload(sessionId, "segment0.m4s", new byte[] {1});

    assertThat(firstDelivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);

    transcodeExecutor.markDead(sessionId);
    var secondDelivery = deliverAsync(sessionId, "segment1.m4s");
    transcodeExecutor.awaitStartedTargetCount(2);
    upload(sessionId, "segment1.m4s", new byte[] {2});

    assertThat(secondDelivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(TARGET_A, TARGET_A);
  }

  @Test
  @DisplayName("Should return unrecoverable and mark the variant failed when every target refuses")
  void shouldReturnUnrecoverableAndMarkTheVariantFailedWhenEveryTargetRefuses() {
    var session = startedSession();
    exhaustRecovery(session);
  }

  @Test
  @DisplayName(
      "Should answer subsequent same-window requests with unrecoverable after exhaustion when delivering a segment")
  void
      shouldAnswerSubsequentSameWindowRequestsWithUnrecoverableAfterExhaustionWhenDeliveringSegment() {
    var session = startedSession();
    exhaustRecovery(session);

    var retry =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.m4s");
    var drifted =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment3.m4s");

    assertThat(retry).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(drifted).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should reopen recovery when a never-attempted target becomes eligible")
  void shouldReopenRecoveryWhenNeverAttemptedTargetBecomesEligible() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    exhaustRecovery(session);
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B, TARGET_C));

    var delivery = deliverAsync(sessionId, "segment0.m4s");
    transcodeExecutor.awaitStartedTarget(TARGET_C);
    upload(sessionId, "segment0.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName(
      "Should treat a relocation-distance request as a planned seek restart after failure when delivering a segment")
  void shouldTreatRelocationDistanceRequestAsPlannedSeekRestartAfterFailureWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    exhaustRecovery(session);
    var targetedStartsBefore = transcodeExecutor.getStartedTargets().size();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sessionId, "segment50.m4s");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(sessionId, "segment50.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    // The seek revived the variant through positioning — a planned restart, not failed-window
    // recovery.
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
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sessionId, "segment2.m4s");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    // The replacement also completes without producing the advertised segment.
    transcodeExecutor.markDead(sessionId);

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should exhaust recovery when an accepted replacement exceeds its startup budget")
  void shouldExhaustRecoveryWhenAnAcceptedReplacementExceedsItsStartupBudget() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();
    var startupBudget =
        properties.producerStallThreshold().plus(properties.targetSegmentDuration());

    var delivery = deliverAsync(sessionId, "segment0.m4s");
    try {
      transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
      // Recovery starts the replacement's stall clock after its start returns. Moving the clock
      // before the request looks again would start that clock late.
      awaitNextLook(sessionId);
      var looks = transcodeExecutor.livenessChecks();
      advanceClockThenWake(sessionId, startupBudget.minusMillis(1));
      transcodeExecutor.awaitLivenessCheckCount(looks + 1);
      assertThat(delivery).isNotDone();
      assertThat(transcodeExecutor.isRunning(sessionId, StreamSession.defaultVariant())).isTrue();

      advanceClockThenWake(sessionId, Duration.ofMillis(1));

      assertThat(delivery.get(2, TimeUnit.SECONDS))
          .isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
      assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
      assertThat(transcodeExecutor.isRunning(sessionId, StreamSession.defaultVariant())).isFalse();
    } finally {
      destroy(sessionId);
      delivery.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName(
      "Should keep an encoded job attempt that starts after the first segment until its preroll and first segment are overdue")
  void
      shouldKeepAnEncodedJobAttemptThatStartsAfterTheFirstSegmentUntilItsPrerollAndFirstSegmentAreOverdue()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var startsBefore = transcodeExecutor.getStartedRequests().size();
    var startupBudget =
        properties
            .producerStallThreshold()
            .plus(properties.targetSegmentDuration().multipliedBy(2));

    var delivery = heldDelivery(sessionId, "1080p", "1080p/segment20.m4s");
    try {
      // Moving the clock before recovery first looks at the job attempt would start its stall
      // clock late.
      awaitNextLook(sessionId, "1080p");
      clock.advance(startupBudget.minusMillis(1));
      awaitNextLook(sessionId, "1080p");
      // A request looks again only after its previous pass has returned.
      awaitNextLook(sessionId, "1080p");
      assertThat(delivery).isNotDone();
      assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);

      clock.advance(Duration.ofMillis(1));
      heldRequests().wakeVariant(sessionId, "1080p");
      transcodeExecutor.awaitStartedRequestCount(startsBefore + 2);
      upload(sessionId, "1080p/segment20.m4s", new byte[] {20});

      assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
      assertThat(startedSince(startsBefore))
          .extracting(TranscodeRequest::variantLabel, TranscodeRequest::startSequenceNumber)
          .containsExactly(tuple("1080p", 20), tuple("1080p", 20));
    } finally {
      destroy(sessionId);
      delivery.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("Should start exactly one replacement when two waiters observe one death")
  void shouldStartExactlyOneReplacementWhenTwoWaitersObserveOneDeath() throws Exception {
    var session = startedSession();
    var sessionId = session.getSessionId();
    transcodeExecutor.markDead(sessionId);
    var startsBefore = transcodeExecutor.getStartedRequests().size();
    var looksBefore = transcodeExecutor.livenessChecks();

    var first = deliverAsync(sessionId, "segment0.m4s");
    var second = deliverAsync(sessionId, "segment0.m4s");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    // Give the losing waiter its own recovery pass (superseded by the mutex predicate) before
    // publishing, so the "exactly one start" assertion covers the second waiter's attempt. The
    // winner looks at the producer twice, to find it dead and to confirm that before it replaces
    // it; the loser looks once and finds the replacement running.
    transcodeExecutor.awaitLivenessCheckCount(looksBefore + 3);
    upload(sessionId, "segment0.m4s", new byte[] {1});

    assertThat(first.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(second.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
  }

  @Test
  @DisplayName("Should end the wait promptly when the session is destroyed")
  void shouldEndTheWaitPromptlyWhenTheSessionIsDestroyed() throws Exception {
    var session = startedSession();

    // Let the delivery reach its wait, then destroy the session; the destruction wakes it.
    var delivery = heldDelivery(session.getSessionId(), "segment1.m4s");
    destroy(session.getSessionId());

    assertThat(delivery.get(1, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
  }

  @Test
  @DisplayName(
      "Should end a held request when its session is destroyed while recovery replaces the producer")
  void shouldEndHeldRequestWhenItsSessionIsDestroyedWhileRecoveryReplacesTheProducer()
      throws Exception {
    var gatingExecutor = new EvidenceGatingExecutor();
    var sessionMutex = new ContendedSessionMutex();
    transcodeExecutor = gatingExecutor;
    var rig =
        rigBuilder(gatingExecutor, segmentStore).sessionMutex(sessionMutex.mutexFactory()).build();
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
    var sessionId = startedSession().getSessionId();
    gatingExecutor.markDead(sessionId);
    gatingExecutor.holdTargetedStarts();

    var delivery = deliverAsync(sessionId, "segment0.m4s");
    gatingExecutor.awaitTargetedStartEntered();
    var destroy = CompletableFuture.runAsync(() -> destroy(sessionId));
    sessionMutex.awaitContention();
    gatingExecutor.releaseTargetedStarts();

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.SessionEnded.class);
    destroy.get(2, TimeUnit.SECONDS);
  }

  @Test
  @DisplayName(
      "Should return cancelled and restore the interrupt without touching the producer when delivering a segment")
  void shouldReturnCancelledAndRestoreTheInterruptWithoutTouchingTheProducerWhenDeliveringSegment()
      throws Exception {
    var session = startedSession();
    var outcome = new AtomicReference<SegmentDelivery>();
    var interruptRestored = new AtomicBoolean();
    var waiter =
        new Thread(
            () -> {
              outcome.set(
                  coordinator.deliver(
                      session.getSessionId(), StreamSession.defaultVariant(), "segment1.m4s"));
              interruptRestored.set(Thread.currentThread().isInterrupted());
            });
    var looks = transcodeExecutor.livenessChecks();
    waiter.start();
    // The waiter is in or near its wait for a wake once it has run a liveness check; interrupting
    // then must surface as Cancelled with the interrupt flag restored.
    transcodeExecutor.awaitLivenessCheckCount(looks + 1);

    waiter.interrupt();
    waiter.join(2000);

    assertThat(outcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);
    assertThat(interruptRestored).isTrue();
    assertThat(transcodeExecutor.getStopped()).isEmpty();
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should map an init segment request to the current run's start sequence number when delivering a segment")
  void shouldMapInitSegmentRequestToTheCurrentRunsStartSequenceNumberWhenDeliveringSegment()
      throws Exception {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    session.setCurrentSegment(2);
    lifecycle.startFirstListedVariant(session);
    transcodeExecutor.markDead(session.getSessionId());
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "init.mp4");
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    upload(session.getSessionId(), "init.mp4", new byte[] {1});

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
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();

    var delivery = deliverAsync(sessionId, "segment1.m4s");
    transcodeExecutor.awaitStartedTarget(ExecutionTargetId.LOCAL);
    upload(sessionId, "segment1.m4s", new byte[] {1});

    // A failed resume enters recovery instead of escaping as a raw server error.
    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should exhaust to unrecoverable when a failed resume has no willing target")
  void shouldExhaustToUnrecoverableWhenFailedResumeHasNoWillingTarget() {
    var session = startedSession();
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);

    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment1.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName(
      "Should keep a seek revival healthy while an exhausting waiter races it when delivering a segment")
  void shouldKeepSeekRevivalHealthyWhileExhaustingWaiterRacesItWhenDeliveringSegment()
      throws Exception {
    var trapStore = new TrapSegmentStore();
    var rig = rigWith(transcodeExecutor, trapStore);
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    runtimeRegistry.save(session);
    rig.lifecycle().startFirstListedVariant(session);

    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);

    // The exhauster gets trapped between markExhausted (variant now FAILED) and its return, the
    // window where a racing revival must not be clobbered or wedged.
    var exhausterOutcome = new AtomicReference<SegmentDelivery>();
    var exhauster =
        new Thread(
            () ->
                exhausterOutcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment0.m4s")),
            "exhauster");
    trapStore.armTrap(
        exhauster, () -> session.getHandle().orElseThrow().status() == TranscodeStatus.FAILED);
    exhauster.start();
    assertThat(trapStore.reachedTrap.await(5, TimeUnit.SECONDS)).isTrue();

    // A planned seek revives the variant with a fresh attempt while the exhauster is trapped.
    var seekerOutcome = new AtomicReference<SegmentDelivery>();
    var seeker =
        new Thread(
            () ->
                seekerOutcome.set(
                    rig.coordinator()
                        .deliver(sessionId, StreamSession.defaultVariant(), "segment50.m4s")),
            "seeker");
    var startsBefore = transcodeExecutor.getStartedRequests().size();
    seeker.start();
    transcodeExecutor.awaitStartedRequestCount(startsBefore + 1);
    // Whatever step the relocated seeker is in, the interrupt ends its wait for a wake.
    seeker.interrupt();
    seeker.join(2000);
    assertThat(seekerOutcome.get()).isInstanceOf(SegmentDelivery.Cancelled.class);

    trapStore.releaseTrap.countDown();
    exhauster.join(2000);
    assertThat(exhausterOutcome.get()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);

    // The revived attempt dies; targets now accept. The stale exhausted window must not survive:
    // a fresh recovery window opens and replaces the producer instead of spinning forever.
    transcodeExecutor.acceptTarget(TARGET_A);
    transcodeExecutor.acceptTarget(TARGET_B);
    transcodeExecutor.markDead(sessionId);
    var targetedStartsBefore = transcodeExecutor.getStartedTargets().size();

    var recovered =
        CompletableFuture.supplyAsync(
            () ->
                rig.coordinator()
                    .deliver(sessionId, StreamSession.defaultVariant(), "segment50.m4s"));
    transcodeExecutor.awaitStartedTargetCount(targetedStartsBefore + 1);
    trapStore.addSegment(sessionId, "segment50.m4s", new byte[] {1});
    heldRequests().wakeVariant(sessionId, StreamSession.defaultVariant());

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
    rig.lifecycle().startFirstListedVariant(session);
    gatingExecutor.markDead(sessionId);
    var streamingService =
        HlsStreamingService.builder()
            .meterRegistry(new SimpleMeterRegistry())
            .transcodeExecutor(gatingExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .producerLifecycle(rig.lifecycle())
            .deliveryCoordinator(rig.coordinator())
            .build();

    gatingExecutor.holdTargetedStarts();
    var replace =
        CompletableFuture.supplyAsync(
            () ->
                rig.lifecycle()
                    .recover(
                        earliestArrivingSegmentRequestBuilder()
                            .sessionId(sessionId)
                            .segmentName("segment1.m4s")
                            .build()));
    gatingExecutor.awaitTargetedStartEntered();

    // Destroy must serialize with the in-flight replace instead of losing to its save. The latch
    // proves the destroy thread is running before the replace is released; the session mutex then
    // orders removal and save deterministically regardless of which reaches the lock first.
    var destroyStarted = new CountDownLatch(1);
    var destroy =
        CompletableFuture.runAsync(
            () -> {
              destroyStarted.countDown();
              streamingService.destroySession(sessionId);
            });
    assertThat(destroyStarted.await(5, TimeUnit.SECONDS)).isTrue();
    gatingExecutor.releaseTargetedStarts();
    replace.get(5, TimeUnit.SECONDS);
    destroy.get(5, TimeUnit.SECONDS);

    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
    assertThat(gatingExecutor.getStopped()).contains(sessionId);
  }

  private StreamSession startedLadderSession() {
    var session = defaultLadderSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private List<String> runningVariants(StreamSession session) {
    return session.getVariants().stream()
        .map(QualityVariant::label)
        .filter(label -> transcodeExecutor.isRunning(session.getSessionId(), label))
        .toList();
  }

  private List<TranscodeRequest> startedSince(int startedBefore) {
    var started = transcodeExecutor.getStartedRequests();
    return started.subList(startedBefore, started.size());
  }

  private void storeInitializationSegments(StreamSession session) {
    for (var variant : session.getVariants()) {
      upload(session.getSessionId(), variant.label() + "/init.mp4", new byte[] {0x66});
    }
  }

  @Test
  @DisplayName(
      "Should start the variant at the requested segment when a media segment of a variant with no job attempt is requested")
  void
      shouldStartTheVariantAtTheRequestedSegmentWhenAMediaSegmentOfAVariantWithNoJobAttemptIsRequested()
          throws Exception {
    var session = startedLadderSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "720p", "720p/segment7.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);
    upload(session.getSessionId(), "720p/segment7.m4s", new byte[] {7});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("720p");
              assertThat(request.startSequenceNumber()).isEqualTo(7);
              assertThat(request.width()).isEqualTo(1280);
            });
    assertThat(runningVariants(session)).containsExactly("720p");
  }

  @Test
  @DisplayName(
      "Should start the variant after the newest requested segment and stop no job attempt when the initialization segment of a variant with no job attempt is requested")
  void
      shouldStartTheVariantAfterTheNewestRequestedSegmentAndStopNoJobAttemptWhenTheInitializationSegmentOfAVariantWithNoJobAttemptIsRequested()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var seek = deliverAsync(sessionId, "480p", "480p/segment20.m4s");
    transcodeExecutor.awaitStartedRequestCount(2);
    upload(sessionId, "480p/segment20.m4s", new byte[] {20});
    assertThat(seek.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    storeInitializationSegments(session);

    var delivery = coordinator.deliver(sessionId, "1080p", "1080p/init.mp4");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(2))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("1080p");
              assertThat(request.startSequenceNumber()).isEqualTo(21);
            });
    assertThat(runningVariants(session)).containsExactlyInAnyOrder("480p", "1080p");
  }

  @Test
  @DisplayName(
      "Should start a switched variant once when the player requests its initialization segment and then the segment after the last one it received")
  void
      shouldStartASwitchedVariantOnceWhenThePlayerRequestsItsInitializationSegmentAndThenTheSegmentAfterTheLastOneItReceived()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    for (var index = 0; index < 10; index++) {
      var segmentName = "480p/" + SegmentNames.mediaSegmentName(index);
      upload(sessionId, segmentName, new byte[] {1});
      assertThat(coordinator.deliver(sessionId, "480p", segmentName))
          .isInstanceOf(SegmentDelivery.Ready.class);
    }

    storeInitializationSegments(session);
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    assertThat(coordinator.deliver(sessionId, "1080p", "1080p/init.mp4"))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var delivery = heldDelivery(sessionId, "1080p", "1080p/segment10.m4s");
    upload(sessionId, "1080p/segment10.m4s", new byte[] {10});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("1080p");
              assertThat(request.startSequenceNumber()).isEqualTo(10);
            });
  }

  @Test
  @DisplayName(
      "Should start a switched variant once at the held segment when the player leaves a variant before that segment is stored")
  void
      shouldStartASwitchedVariantOnceAtTheHeldSegmentWhenThePlayerLeavesAVariantBeforeThatSegmentIsStored()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    for (var index = 0; index < 9; index++) {
      upload(sessionId, "480p/" + SegmentNames.mediaSegmentName(index), new byte[] {1});
    }

    var abandoned = heldDelivery(sessionId, "480p", "480p/segment9.m4s");
    storeInitializationSegments(session);
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    assertThat(coordinator.deliver(sessionId, "360p", "360p/init.mp4"))
        .isInstanceOf(SegmentDelivery.Ready.class);
    var delivery = heldDelivery(sessionId, "360p", "360p/segment9.m4s");
    upload(sessionId, "360p/segment9.m4s", new byte[] {9});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(abandoned.get(2, TimeUnit.SECONDS))
        .isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("360p");
              assertThat(request.startSequenceNumber()).isEqualTo(9);
            });
  }

  @Test
  @DisplayName(
      "Should stop the job attempts of every other video variant when a media segment of one video variant is requested")
  void
      shouldStopTheJobAttemptsOfEveryOtherVideoVariantWhenAMediaSegmentOfOneVideoVariantIsRequested()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    storeInitializationSegments(session);
    coordinator.deliver(sessionId, "1080p", "1080p/init.mp4");
    coordinator.deliver(sessionId, "720p", "720p/init.mp4");
    assertThat(runningVariants(session)).containsExactlyInAnyOrder("480p", "1080p", "720p");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(sessionId, "360p", "360p/segment0.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);
    upload(sessionId, "360p/segment0.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(runningVariants(session)).containsExactly("360p");
    assertThat(session.getVariantHandles()).containsOnlyKeys("360p");
  }

  @Test
  @DisplayName(
      "Should stop the other video variants and start nothing when the requested media segment is stored")
  void shouldStopTheOtherVideoVariantsAndStartNothingWhenTheRequestedMediaSegmentIsStored() {
    var session = startedLadderSession();
    upload(session.getSessionId(), "720p/segment3.m4s", new byte[] {3});
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = coordinator.deliver(session.getSessionId(), "720p", "720p/segment3.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(runningVariants(session)).isEmpty();
    assertThat(startedSince(startedBefore)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should start the requested variant and drop the suspended variant when a suspended stream session receives a media request for another variant")
  void
      shouldStartTheRequestedVariantAndDropTheSuspendedVariantWhenASuspendedStreamSessionReceivesAMediaRequestForAnotherVariant()
          throws Exception {
    var session = startedLadderSession();
    lifecycle.suspend(session.getSessionId());
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "1080p", "1080p/segment4.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);
    upload(session.getSessionId(), "1080p/segment4.m4s", new byte[] {4});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("1080p");
              assertThat(request.startSequenceNumber()).isEqualTo(4);
            });
    assertThat(session.getVariantHandles()).containsOnlyKeys("1080p");
    assertThat(session.isSuspended()).isFalse();
  }

  @Test
  @DisplayName("Should relocate only the requested variant when the player seeks")
  void shouldRelocateOnlyTheRequestedVariantWhenThePlayerSeeks() throws Exception {
    var session = startedLadderSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var delivery = deliverAsync(session.getSessionId(), "480p", "480p/segment100.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);
    upload(session.getSessionId(), "480p/segment100.m4s", new byte[] {1});

    assertThat(delivery.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("480p");
              assertThat(request.startSequenceNumber()).isEqualTo(100);
            });
    assertThat(runningVariants(session)).containsExactly("480p");
  }

  @Test
  @DisplayName(
      "Should answer a held request as unrecoverable without restarting its variant when a request for another video variant stops that variant")
  void
      shouldAnswerAHeldRequestAsUnrecoverableWithoutRestartingItsVariantWhenARequestForAnotherVideoVariantStopsThatVariant()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var held = heldDelivery(sessionId, "480p", "480p/segment1.m4s");

    var switched = deliverAsync(sessionId, "720p", "720p/segment1.m4s");
    transcodeExecutor.awaitStartedRequestCount(2);

    assertThat(held.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    upload(sessionId, "720p/segment1.m4s", new byte[] {1});
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(transcodeExecutor.getStartedRequests())
        .extracting(TranscodeRequest::variantLabel)
        .containsExactly("480p", "720p");
  }

  @Test
  @DisplayName(
      "Should answer unrecoverable when no worker can start the variant that a media segment request needs")
  void shouldAnswerUnrecoverableWhenNoWorkerCanStartTheVariantThatAMediaSegmentRequestNeeds() {
    var session = startedLadderSession();
    transcodeExecutor.failUntargetedStarts();

    var delivery = coordinator.deliver(session.getSessionId(), "720p", "720p/segment3.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getVariantHandle("720p")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should stop no job attempt when a media segment of a variant outside the ladder is requested")
  void shouldStopNoJobAttemptWhenAMediaSegmentOfAVariantOutsideTheLadderIsRequested() {
    var session = startedLadderSession();

    var delivery = coordinator.deliver(session.getSessionId(), "2160p", "2160p/segment0.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.SessionEnded.class);
    assertThat(runningVariants(session)).containsExactly("480p");
  }

  @Test
  @DisplayName(
      "Should leave one video variant running when media segment requests for two variants overlap")
  void shouldLeaveOneVideoVariantRunningWhenMediaSegmentRequestsForTwoVariantsOverlap()
      throws Exception {
    var session = defaultLadderSessionBuilder().build();
    var sessionId = session.getSessionId();
    var gate = new SwitchGate(() -> !transcodeExecutor.isRunning(sessionId, "480p"));
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .clock(clock)
            .heldRequests(heldRequests())
            .sessionMutex(gate.mutexFactory())
            .build();
    runtimeRegistry.save(session);
    rig.lifecycle().startFirstListedVariant(session);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var switchTo720p =
          executor.submit(
              () ->
                  gate.gateCurrentThread(
                      () -> rig.coordinator().deliver(sessionId, "720p", "720p/segment5.m4s")));
      gate.awaitGatedOrStarted(transcodeExecutor, 2);
      var startedBefore = transcodeExecutor.getStartedRequests().size();
      var switchTo1080p =
          executor.submit(
              () -> rig.coordinator().deliver(sessionId, "1080p", "1080p/segment5.m4s"));
      transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);
      upload(sessionId, "1080p/segment5.m4s", new byte[] {5});
      assertThat(switchTo1080p.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);

      gate.releaseAndAwaitHeldStep();
      upload(sessionId, "720p/segment5.m4s", new byte[] {5});
      switchTo720p.get(2, TimeUnit.SECONDS);
    }

    assertThat(runningVariants(session)).hasSize(1);
  }

  // Plays the first-listed variant through the given number of media segments.
  private StreamSession ladderSessionPlayingFirstListedVariant(int deliveredSegments) {
    var session = startedLadderSession();
    for (var index = 0; index < deliveredSegments; index++) {
      var segmentName = "480p/" + SegmentNames.mediaSegmentName(index);
      upload(session.getSessionId(), segmentName, new byte[] {1});
      assertThat(coordinator.deliver(session.getSessionId(), "480p", segmentName))
          .isInstanceOf(SegmentDelivery.Ready.class);
    }

    return session;
  }

  @Test
  @DisplayName(
      "Should keep the variant that the player leaves running until the switched variant stores its first media segment when the player switches variants")
  void
      shouldKeepTheVariantThatThePlayerLeavesRunningUntilTheSwitchedVariantStoresItsFirstMediaSegmentWhenThePlayerSwitchesVariants()
          throws Exception {
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    storeInitializationSegments(session);
    assertThat(coordinator.deliver(sessionId, "1080p", "1080p/init.mp4"))
        .isInstanceOf(SegmentDelivery.Ready.class);

    var switched = heldDelivery(sessionId, "1080p", "1080p/segment3.m4s");

    assertThat(runningVariants(session)).containsExactlyInAnyOrder("480p", "1080p");
    upload(sessionId, "1080p/segment3.m4s", new byte[] {3});
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(runningVariants(session)).containsExactly("1080p");
    assertThat(session.getVariantHandles()).containsOnlyKeys("1080p");
  }

  @Test
  @DisplayName(
      "Should serve the variant that the player left without restarting it when the player switches back before the switched variant stores a media segment")
  void
      shouldServeTheVariantThatThePlayerLeftWithoutRestartingItWhenThePlayerSwitchesBackBeforeTheSwitchedVariantStoresAMediaSegment()
          throws Exception {
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    var startedBefore = transcodeExecutor.getStartedRequests().size();
    var switched = heldDelivery(sessionId, "1080p", "1080p/segment3.m4s");

    var switchedBack = deliverAsync(sessionId, "480p", "480p/segment3.m4s");
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    upload(sessionId, "480p/segment3.m4s", new byte[] {3});

    assertThat(switchedBack.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore))
        .extracting(TranscodeRequest::variantLabel)
        .containsExactly("1080p");
    assertThat(runningVariants(session)).containsExactly("480p");
  }

  @Test
  @DisplayName(
      "Should keep running the variant that the player left when the switched variant's job attempt fails on every target")
  void
      shouldKeepRunningTheVariantThatThePlayerLeftWhenTheSwitchedVariantsJobAttemptFailsOnEveryTarget()
          throws Exception {
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    var switched = heldDelivery(sessionId, "1080p", "1080p/segment3.m4s");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    transcodeExecutor.markDead(sessionId, "1080p");

    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(runningVariants(session)).containsExactly("480p");
    var switchedBack = deliverAsync(sessionId, "480p", "480p/segment3.m4s");
    upload(sessionId, "480p/segment3.m4s", new byte[] {3});
    assertThat(switchedBack.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep only the variant that the player last received when the player switches twice before either switched variant stores a media segment")
  void
      shouldKeepOnlyTheVariantThatThePlayerLastReceivedWhenThePlayerSwitchesTwiceBeforeEitherSwitchedVariantStoresAMediaSegment()
          throws Exception {
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    var firstSwitch = heldDelivery(sessionId, "720p", "720p/segment3.m4s");
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var secondSwitch = deliverAsync(sessionId, "1080p", "1080p/segment3.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);

    assertThat(firstSwitch.get(2, TimeUnit.SECONDS))
        .isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(runningVariants(session)).containsExactlyInAnyOrder("480p", "1080p");
    upload(sessionId, "1080p/segment3.m4s", new byte[] {3});
    assertThat(secondSwitch.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(runningVariants(session)).containsExactly("1080p");
  }

  @Test
  @DisplayName(
      "Should stop the variant that the player leaves when the switched variant's segment lies past that variant's relocation gap")
  void
      shouldStopTheVariantThatThePlayerLeavesWhenTheSwitchedVariantsSegmentLiesPastThatVariantsRelocationGap()
          throws Exception {
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var seek = deliverAsync(sessionId, "1080p", "1080p/segment100.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);

    assertThat(runningVariants(session)).containsExactly("1080p");
    upload(sessionId, "1080p/segment100.m4s", new byte[] {1});
    assertThat(seek.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
  }

  @Test
  @DisplayName(
      "Should stop the variant that the player leaves to start the switched variant when the transcode limit leaves no room")
  void
      shouldStopTheVariantThatThePlayerLeavesToStartTheSwitchedVariantWhenTheTranscodeLimitLeavesNoRoom()
          throws Exception {
    properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(1)
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(STALL_THRESHOLD)
            .build();
    var rig = rigWith(transcodeExecutor);
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var switched = deliverAsync(sessionId, "1080p", "1080p/segment3.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);

    assertThat(runningVariants(session)).containsExactly("1080p");
    upload(sessionId, "1080p/segment3.m4s", new byte[] {3});
    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.variantLabel()).isEqualTo("1080p");
              assertThat(request.startSequenceNumber()).isEqualTo(3);
            });
  }

  @Test
  @DisplayName(
      "Should drop the suspended variant that the player left when a suspended stream session resumes the switched variant")
  void
      shouldDropTheSuspendedVariantThatThePlayerLeftWhenASuspendedStreamSessionResumesTheSwitchedVariant()
          throws Exception {
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/segment3.m4s");
    lifecycle.suspend(sessionId);
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    var resumed = deliverAsync(sessionId, "1080p", "1080p/segment3.m4s");
    transcodeExecutor.awaitStartedRequestCount(startedBefore + 1);
    upload(sessionId, "1080p/segment3.m4s", new byte[] {3});

    assertThat(resumed.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startedSince(startedBefore))
        .extracting(TranscodeRequest::variantLabel)
        .containsExactly("1080p");
    assertThat(session.getVariantHandles()).containsOnlyKeys("1080p");
    assertThat(runningVariants(session)).containsExactly("1080p");
  }

  @Test
  @DisplayName(
      "Should keep the variant that the player switched back to running when the switched variant's first media segment is read while the player switches back")
  void
      shouldKeepTheVariantThatThePlayerSwitchedBackToRunningWhenTheSwitchedVariantsFirstMediaSegmentIsReadWhileThePlayerSwitchesBack()
          throws Exception {
    var store = new ReadTrapSegmentStore("1080p/segment3.m4s");
    segmentStore = store;
    var rig = rigWith(transcodeExecutor, store);
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
    var session = ladderSessionPlayingFirstListedVariant(3);
    var sessionId = session.getSessionId();
    var startedBefore = transcodeExecutor.getStartedRequests().size();
    var switched = heldDelivery(sessionId, "1080p", "1080p/segment3.m4s");
    upload(sessionId, "1080p/segment3.m4s", new byte[] {3});
    store.awaitTrappedRead();

    var switchedBack = heldDelivery(sessionId, "480p", "480p/segment3.m4s");
    store.releaseTrappedRead();

    assertThat(switched.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    upload(sessionId, "480p/segment3.m4s", new byte[] {3});
    assertThat(switchedBack.get(2, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(runningVariants(session)).containsExactly("480p");
    assertThat(startedSince(startedBefore))
        .extracting(TranscodeRequest::variantLabel)
        .containsExactly("1080p");
  }

  /**
   * Holds one request's thread at the session mutex once the given condition holds, so a second
   * request can run between the first request's steps.
   */
  private static final class SwitchGate {

    private final BooleanSupplier holdWhen;
    private final CountDownLatch gated = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch heldStepDone = new CountDownLatch(1);
    private final ReentrantLock lock = new GateLock();
    private volatile Thread gatedThread;

    private SwitchGate(BooleanSupplier holdWhen) {
      this.holdWhen = holdWhen;
    }

    private MutexFactory<UUID> mutexFactory() {
      return new MutexFactory<>() {
        @Override
        public ReentrantLock getMutex(UUID sessionId) {
          return lock;
        }
      };
    }

    private SegmentDelivery gateCurrentThread(Supplier<SegmentDelivery> request) {
      gatedThread = Thread.currentThread();
      return request.get();
    }

    // Waits on the gate's latch in bounded steps, so no thread stays parked when the request never
    // reaches the gate.
    private void awaitGatedOrStarted(FakeTranscodeExecutor executor, int startedRequestCount)
        throws InterruptedException {
      var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!gated.await(10, TimeUnit.MILLISECONDS)) {
        if (executor.getStartedRequests().size() >= startedRequestCount) {
          return;
        }

        assertThat(System.nanoTime())
            .as("the request must reach the switch gate or start its variant")
            .isLessThan(deadline);
      }
    }

    // Returns after the held step has run, so the test changes nothing during that step.
    private void releaseAndAwaitHeldStep() throws InterruptedException {
      var held = gated.getCount() == 0;
      released.countDown();
      if (held) {
        assertThat(heldStepDone.await(5, TimeUnit.SECONDS))
            .as("the held step must finish")
            .isTrue();
      }
    }

    private final class GateLock extends ReentrantLock {

      private volatile boolean holdingHeldStep;

      @Override
      public void unlock() {
        super.unlock();
        if (Thread.currentThread() == gatedThread && holdingHeldStep) {
          holdingHeldStep = false;
          heldStepDone.countDown();
        }
      }

      @Override
      public void lock() {
        if (Thread.currentThread() == gatedThread
            && gated.getCount() > 0
            && holdWhen.getAsBoolean()) {
          gated.countDown();
          try {
            assertThat(released.await(5, TimeUnit.SECONDS))
                .as("the switch gate must be released")
                .isTrue();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting at the switch gate", e);
          }

          super.lock();
          holdingHeldStep = true;
          return;
        }

        super.lock();
      }
    }
  }

  private void exhaustRecovery(StreamSession session) {
    transcodeExecutor.setExecutionTargets(List.of(TARGET_A, TARGET_B));
    transcodeExecutor.refuseTarget(TARGET_A);
    transcodeExecutor.refuseTarget(TARGET_B);
    transcodeExecutor.markDead(session.getSessionId());
    var delivery =
        coordinator.deliver(session.getSessionId(), StreamSession.defaultVariant(), "segment0.m4s");
    assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
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
        awaitTrapRelease();
      }

      return super.segmentExists(sessionId, segmentName);
    }

    private void awaitTrapRelease() {
      try {
        if (!releaseTrap.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting to release trapped segment lookup");
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Holds the first read of one segment until the test releases it. */
  private static final class ReadTrapSegmentStore extends FakeSegmentStore {

    private final String trappedSegmentName;
    private final CountDownLatch reachedTrap = new CountDownLatch(1);
    private final CountDownLatch releaseTrap = new CountDownLatch(1);
    private final AtomicBoolean tripped = new AtomicBoolean();

    private ReadTrapSegmentStore(String trappedSegmentName) {
      this.trappedSegmentName = trappedSegmentName;
    }

    private void awaitTrappedRead() throws InterruptedException {
      assertThat(reachedTrap.await(5, TimeUnit.SECONDS))
          .as("a request must read the trapped segment")
          .isTrue();
    }

    private void releaseTrappedRead() {
      releaseTrap.countDown();
    }

    @Override
    public byte[] readSegment(UUID sessionId, String segmentName) {
      if (segmentName.equals(trappedSegmentName) && tripped.compareAndSet(false, true)) {
        reachedTrap.countDown();
        awaitRelease();
      }

      return super.readSegment(sessionId, segmentName);
    }

    private void awaitRelease() {
      try {
        if (!releaseTrap.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Timed out waiting to release the trapped segment read");
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Removes a session immediately after returning one lookup result, reproducing a destroy race.
   */
  private static final class DestroyAfterLookupRegistry extends FakeRuntimeStreamSessionRegistry {

    private Runnable destroyAction;

    private void destroyAfterNextLookup(Runnable action) {
      destroyAction = action;
    }

    @Override
    public Optional<StreamSession> findById(UUID sessionId) {
      var result = super.findById(sessionId);
      var action = destroyAction;
      if (action != null) {
        destroyAction = null;
        action.run();
      }

      return result;
    }
  }

  /** Signals when a thread waits for the session mutex while another thread holds it. */
  private static final class ContendedSessionMutex {

    private final CountDownLatch contended = new CountDownLatch(1);
    private final ReentrantLock lock = new ContentionSignalingLock();

    private MutexFactory<UUID> mutexFactory() {
      return new MutexFactory<>() {
        @Override
        public ReentrantLock getMutex(UUID sessionId) {
          return lock;
        }
      };
    }

    private void awaitContention() throws InterruptedException {
      assertThat(contended.await(5, TimeUnit.SECONDS))
          .as("a thread must wait for the session mutex")
          .isTrue();
    }

    private final class ContentionSignalingLock extends ReentrantLock {

      @Override
      public void lock() {
        if (tryLock()) {
          return;
        }

        contended.countDown();
        super.lock();
      }
    }
  }

  /** Holds a targeted start open while session destruction races recovery. */
  private static final class EvidenceGatingExecutor extends FakeTranscodeExecutor {

    private volatile CountDownLatch targetedStartEntered;
    private volatile CountDownLatch targetedStartGate;

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

    private static void awaitQuietly(CountDownLatch latch) {
      try {
        latch.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
