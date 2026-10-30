package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultLadderSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Superseded Segment Request Tests")
class SupersededSegmentRequestTest {

  private static final String VARIANT = "480p";
  private static final int RACE_ROUNDS = 25;

  private final List<UUID> sessionIds = new ArrayList<>();
  private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;
  private SegmentDeliveryCoordinator coordinator;

  @BeforeEach
  void setUp() {
    useSegmentDuration(Duration.ofSeconds(6));
  }

  // Destroying each session ends every request still held on it, so no request outlives its test.
  @AfterEach
  void tearDown() throws InterruptedException {
    sessionIds.forEach(
        sessionId -> {
          runtimeRegistry.removeById(sessionId);
          coordinator.forgetSession(sessionId);
        });
    requests.shutdownNow();
    assertThat(requests.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  // The relocation gap is 24 s: 4 media segments at 6 s segments, and 2 at 12 s segments.
  private void useSegmentDuration(Duration segmentDuration) {
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .targetSegmentDuration(segmentDuration)
                    .producerStallThreshold(Duration.ofMinutes(10))
                    .build())
            .runtimeRegistry(runtimeRegistry)
            .heldRequests(transcodeExecutor.heldSegmentRequests())
            .build();
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
  }

  // A ladder session whose first-listed variant, 480p, runs from segment 0.
  private StreamSession startedLadderSession() {
    var session = defaultLadderSessionBuilder().build();
    runtimeRegistry.save(session);
    sessionIds.add(session.getSessionId());
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private CompletableFuture<SegmentDelivery> deliverAsync(UUID sessionId, int segmentIndex) {
    return CompletableFuture.supplyAsync(
        () -> coordinator.deliver(sessionId, VARIANT, segmentName(segmentIndex)), requests);
  }

  // Returns once the request has looked at its variant and found no segment, so that it is held.
  private CompletableFuture<SegmentDelivery> heldDelivery(UUID sessionId, int segmentIndex) {
    var looks = transcodeExecutor.livenessChecks();
    var delivery = deliverAsync(sessionId, segmentIndex);
    transcodeExecutor.awaitLivenessCheckCount(looks + 1);
    return delivery;
  }

  // Returns once the request has restarted the variant at its segment.
  private CompletableFuture<SegmentDelivery> relocatingDelivery(UUID sessionId, int segmentIndex) {
    var starts = transcodeExecutor.getStartedRequests().size();
    var delivery = deliverAsync(sessionId, segmentIndex);
    transcodeExecutor.awaitStartedRequestCount(starts + 1);
    return delivery;
  }

  private void upload(UUID sessionId, int segmentIndex) {
    segmentStore.addSegment(sessionId, segmentName(segmentIndex), new byte[] {1});
    transcodeExecutor.heldSegmentRequests().wakeVariant(sessionId, VARIANT);
  }

  // Looks run one at a time under the session lock, so a second look after this call begins only
  // once the look running now, such as a relocating request's, has ended.
  private void awaitEndOfCurrentLook() {
    transcodeExecutor.awaitLivenessCheckCount(transcodeExecutor.livenessChecks() + 2);
  }

  // Ends the variant's job attempt and returns once a held request has started its replacement.
  private void endJobAttemptAndAwaitReplacement(UUID sessionId) {
    var replacements = transcodeExecutor.getStartedTargets().size();
    transcodeExecutor.markDead(sessionId, VARIANT);
    transcodeExecutor.awaitStartedTargetCount(replacements + 1);
  }

  private static String segmentName(int segmentIndex) {
    return VARIANT + "/segment" + segmentIndex + ".m4s";
  }

  private List<Integer> startSegments(UUID sessionId) {
    return transcodeExecutor.getStartedRequests().stream()
        .filter(request -> request.sessionId().equals(sessionId))
        .map(TranscodeRequest::startSequenceNumber)
        .toList();
  }

  private static int runningStartSegment(StreamSession session) {
    return session.getVariantHandle(VARIANT).orElseThrow().startSequenceNumber();
  }

  private static SegmentDelivery answer(CompletableFuture<SegmentDelivery> delivery)
      throws Exception {
    return delivery.get(5, TimeUnit.SECONDS);
  }

  // Wakes the variant's held requests without pause until closed, as a run of uploads and ended
  // job attempts does, so that every held request keeps looking at the variant. The waker yields
  // after
  // each wake so that the held requests still run on a two-core runner.
  private AutoCloseable wakingContinuously(UUID sessionId) {
    var waking = new AtomicBoolean(true);
    var waker =
        Thread.ofPlatform()
            .daemon()
            .start(
                () -> {
                  while (waking.get()) {
                    transcodeExecutor.heldSegmentRequests().wakeVariant(sessionId, VARIANT);
                    Thread.yield();
                  }
                });
    return () -> {
      waking.set(false);
      assertThat(waker.join(Duration.ofSeconds(5))).isTrue();
    };
  }

  @Test
  @DisplayName(
      "Should leave the variant at the newer request's segment when an older held request looks again after the newer request relocated the variant")
  void
      shouldLeaveTheVariantAtTheNewerRequestsSegmentWhenAnOlderHeldRequestLooksAgainAfterTheNewerRequestRelocatedTheVariant()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var older = heldDelivery(sessionId, 1);

    var newer = relocatingDelivery(sessionId, 100);

    assertThat(answer(older)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
    upload(sessionId, 100);
    assertThat(answer(newer)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startSegments(sessionId)).containsExactly(0, 100);
    assertThat(runningStartSegment(session)).isEqualTo(100);
  }

  @ParameterizedTest(name = "{0} s segments, relocation gap of {1} segments")
  @CsvSource({"6, 4", "12, 2"})
  @DisplayName(
      "Should cause no relocation from the older request once the newer request relocated the variant when held requests are woken without pause")
  void
      shouldCauseNoRelocationFromTheOlderRequestOnceTheNewerRequestRelocatedTheVariantWhenHeldRequestsAreWokenWithoutPause(
          int segmentSeconds, int gapSegments) throws Exception {
    useSegmentDuration(Duration.ofSeconds(segmentSeconds));
    var newerSegment = 1 + gapSegments + 1;

    for (var round = 0; round < RACE_ROUNDS; round++) {
      var session = startedLadderSession();
      var sessionId = session.getSessionId();
      var older = heldDelivery(sessionId, 1);

      try (var _ = wakingContinuously(sessionId)) {
        var newer = deliverAsync(sessionId, newerSegment);

        assertThat(answer(older)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
        upload(sessionId, newerSegment);
        assertThat(answer(newer)).isInstanceOf(SegmentDelivery.Ready.class);
      }

      assertThat(startSegments(sessionId)).containsExactly(0, newerSegment);
      assertThat(runningStartSegment(session)).isEqualTo(newerSegment);
    }
  }

  @Test
  @DisplayName(
      "Should relocate the variant once for each newer request when seeks alternate between two segments while held requests are woken without pause")
  void
      shouldRelocateTheVariantOnceForEachNewerRequestWhenSeeksAlternateBetweenTwoSegmentsWhileHeldRequestsAreWokenWithoutPause()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var newest = heldDelivery(sessionId, 1);

    // Each newer request moves the variant away from the request before it, which then ends.
    try (var _ = wakingContinuously(sessionId)) {
      for (var seek : List.of(100, 1, 100)) {
        var superseded = newest;
        newest = relocatingDelivery(sessionId, seek);

        assertThat(answer(superseded)).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      }
    }

    upload(sessionId, 100);
    assertThat(answer(newest)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startSegments(sessionId)).containsExactly(0, 100, 1, 100);
  }

  @Test
  @DisplayName(
      "Should relocate the variant backward when a newer request asks for a segment before the job attempt's first segment")
  void
      shouldRelocateTheVariantBackwardWhenANewerRequestAsksForASegmentBeforeTheJobAttemptsFirstSegment()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var seek = relocatingDelivery(sessionId, 100);
    upload(sessionId, 100);
    assertThat(answer(seek)).isInstanceOf(SegmentDelivery.Ready.class);

    var seekBack = relocatingDelivery(sessionId, 20);

    upload(sessionId, 20);
    assertThat(answer(seekBack)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startSegments(sessionId)).containsExactly(0, 100, 20);
    assertThat(runningStartSegment(session)).isEqualTo(20);
  }

  @Test
  @DisplayName(
      "Should answer an older held request with its segment when the job attempt that a newer request started produces that segment")
  void
      shouldAnswerAnOlderHeldRequestWithItsSegmentWhenTheJobAttemptThatANewerRequestStartedProducesThatSegment()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var older = relocatingDelivery(sessionId, 101);

    var newer = relocatingDelivery(sessionId, 100);

    upload(sessionId, 100);
    assertThat(answer(newer)).isInstanceOf(SegmentDelivery.Ready.class);
    upload(sessionId, 101);
    assertThat(answer(older)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startSegments(sessionId)).containsExactly(0, 101, 100);
  }

  @Test
  @DisplayName(
      "Should answer an older held request with its segment from a replacement when the job attempt that a newer request started dies after producing the newer request's segment")
  void
      shouldAnswerAnOlderHeldRequestWithItsSegmentFromAReplacementWhenTheJobAttemptThatANewerRequestStartedDiesAfterProducingTheNewerRequestsSegment()
          throws Exception {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    var older = relocatingDelivery(sessionId, 101);
    var newer = relocatingDelivery(sessionId, 100);
    upload(sessionId, 100);
    assertThat(answer(newer)).isInstanceOf(SegmentDelivery.Ready.class);

    endJobAttemptAndAwaitReplacement(sessionId);

    upload(sessionId, 101);
    assertThat(answer(older)).isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(startSegments(sessionId)).containsExactly(0, 101, 100, 101);
  }

  @Test
  @DisplayName(
      "Should answer both held requests when the job attempt that the newer request started dies before producing either segment")
  void
      shouldAnswerBothHeldRequestsWhenTheJobAttemptThatTheNewerRequestStartedDiesBeforeProducingEitherSegment()
          throws Exception {
    for (var round = 0; round < RACE_ROUNDS; round++) {
      var session = startedLadderSession();
      var sessionId = session.getSessionId();
      var older = relocatingDelivery(sessionId, 101);
      var newer = relocatingDelivery(sessionId, 100);

      // Either request may look first once the job attempt ends.
      try (var _ = wakingContinuously(sessionId)) {
        awaitEndOfCurrentLook();
        endJobAttemptAndAwaitReplacement(sessionId);

        upload(sessionId, 100);
        upload(sessionId, 101);
        assertThat(answer(newer)).isInstanceOf(SegmentDelivery.Ready.class);
        assertThat(answer(older)).isInstanceOf(SegmentDelivery.Ready.class);
      }

      assertThat(startSegments(sessionId)).containsExactly(0, 101, 100, 100);
    }
  }

  @Test
  @DisplayName(
      "Should answer both held requests when the job attempt producing their consecutive segments dies")
  void shouldAnswerBothHeldRequestsWhenTheJobAttemptProducingTheirConsecutiveSegmentsDies()
      throws Exception {
    for (var round = 0; round < RACE_ROUNDS; round++) {
      var session = startedLadderSession();
      var sessionId = session.getSessionId();
      for (var produced = 0; produced < 5; produced++) {
        upload(sessionId, produced);
      }

      var older = heldDelivery(sessionId, 5);
      var newer = heldDelivery(sessionId, 6);

      // Either request may look first once the job attempt ends.
      try (var _ = wakingContinuously(sessionId)) {
        awaitEndOfCurrentLook();
        endJobAttemptAndAwaitReplacement(sessionId);

        // A replacement at segment 6 leaves segment 5 to a relocation by the older request.
        await().atMost(Duration.ofSeconds(5)).until(() -> runningStartSegment(session) == 5);
        upload(sessionId, 5);
        upload(sessionId, 6);
        assertThat(answer(older)).isInstanceOf(SegmentDelivery.Ready.class);
        assertThat(answer(newer)).isInstanceOf(SegmentDelivery.Ready.class);
      }

      assertThat(startSegments(sessionId)).isIn(List.of(0, 5), List.of(0, 6, 5));
    }
  }
}
