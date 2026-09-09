package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.abrSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamingRigFixture;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Producer Lifecycle Service Tests")
class ProducerLifecycleServiceTest {

  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    lifecycle =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .maxConcurrentTranscodes(3)
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .sessionTimeout(Duration.ofSeconds(60))
                    .build())
            .clock(clock)
            .runtimeRegistry(runtimeRegistry)
            .build()
            .lifecycle();
  }

  private StreamSession startedSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  private StreamSession startedAbrSession() {
    var session = abrSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  private StreamSession midTimelineSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 5400, 900);
    return session;
  }

  private void suspendHandle(StreamSession session) {
    session.setHandle(mintHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());
  }

  @Test
  @DisplayName("Should keep previously transcoded segments when relocating")
  void shouldKeepPreviouslyTranscodedSegmentsWhenRelocating() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts");

    // Segments are addressed on the absolute timeline, so earlier segments stay valid.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
    assertThat(segmentStore.readSegment(session.getSessionId(), "segment0.ts")).containsExactly(1);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when session is actively transcoding")
  void shouldNotRestartFfmpegWhenSessionIsActivelyTranscoding() {
    var session = startedSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "segment0.ts");

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should not restart FFmpeg when segment already exists on disk")
  void shouldNotRestartFfmpegWhenSegmentAlreadyExistsOnDisk() {
    var session = startedSession();
    suspendHandle(session);
    segmentStore.addSegment(session.getSessionId(), "segment5.ts", new byte[] {0x47});

    lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts");

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should not throw when positioning nonexistent session")
  void shouldNotThrowWhenPositioningNonexistentSession() {
    assertThatNoException()
        .isThrownBy(() -> lifecycle.ensurePositioned(UUID.randomUUID(), "segment0.ts"));
  }

  @Test
  @DisplayName("Should update last accessed time when resuming suspended session")
  void shouldUpdateLastAccessedTimeWhenResumingSuspendedSession() {
    var session = startedSession();
    suspendHandle(session);
    session.setLastAccessedAt(Instant.now().minusSeconds(200));
    var oldAccessTime = session.getLastAccessedAt();

    lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts");

    assertThat(session.getLastAccessedAt()).isAfter(oldAccessTime);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @ParameterizedTest(name = "{0} → startNumber={1}, seek={2}")
  @DisplayName("Should resume with correct start number when segment name encodes an index")
  @CsvSource({
    "segment0.ts, 0, 0",
    "segment5.ts, 5, 30",
    "segment12.m4s, 12, 72",
    "720p/segment3.ts, 3, 18"
  })
  void shouldResumeWithCorrectStartNumberWhenSegmentNameEncodesIndex(
      String segmentName, int startNumber, int seekPosition) {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), segmentName);

    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isTrue();
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(startNumber);
    assertThat(lastRequest.seekPosition()).isEqualTo(seekPosition);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(2);
  }

  @Test
  @DisplayName("Should not relocate a mid-timeline producer when an init segment is requested")
  void shouldNotRelocateMidTimelineProducerWhenInitSegmentIsRequested() {
    var session = midTimelineSession();
    var startedBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "init.mp4");

    // init.mp4 carries no index; it must never be read as segment 0 and drag the run to the top.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startedBefore);
  }

  @Test
  @DisplayName("Should resume a mid-timeline session at its own start when an init is requested")
  void shouldResumeMidTimelineSessionAtItsOwnStartWhenInitIsRequested() {
    var session = midTimelineSession();
    session.setHandle(session.getHandle().orElseThrow().withStatus(TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());

    lifecycle.ensurePositioned(session.getSessionId(), "init.mp4");

    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(900);
    assertThat(lastRequest.seekPosition()).isEqualTo(5400);
    assertThat(session.getHandle().orElseThrow().startSequenceNumber()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should resume at beginning when segment name has no index")
  void shouldResumeAtBeginningWhenSegmentNameHasNoIndex() {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), "init.mp4");

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startSequenceNumber()).isZero();
    assertThat(lastRequest.seekPosition()).isZero();
  }

  @Test
  @DisplayName("Should restart all variant transcodes when ABR session is resumed")
  void shouldRestartAllVariantTranscodesWhenAbrSessionIsResumed() {
    var session = startedAbrSession();
    var variantLabels = session.getVariants().stream().map(v -> v.label()).toList();
    var previousAttempts =
        variantLabels.stream()
            .map(label -> session.getVariantHandle(label).orElseThrow().attemptId())
            .toList();

    for (var label : variantLabels) {
      session.setVariantHandle(label, mintHandle(1L, TranscodeStatus.SUSPENDED));
      transcodeExecutor.markDead(session.getSessionId(), label);
    }

    var requestsBefore = transcodeExecutor.getStartedRequests().size();
    lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts");
    var resumeRequests =
        transcodeExecutor
            .getStartedRequests()
            .subList(requestsBefore, transcodeExecutor.getStartedRequests().size());

    assertThat(resumeRequests).hasSize(variantLabels.size());
    assertThat(resumeRequests).extracting(TranscodeRequest::startSequenceNumber).containsOnly(5);
    assertThat(resumeRequests).extracting(TranscodeRequest::seekPosition).containsOnly(30);
    assertThat(resumeRequests)
        .extracting(TranscodeRequest::variantLabel)
        .containsExactlyInAnyOrderElementsOf(variantLabels);
    for (var label : variantLabels) {
      assertThat(session.getVariantHandle(label).orElseThrow().status())
          .isEqualTo(TranscodeStatus.ACTIVE);
    }
    assertThat(resumeRequests)
        .extracting(TranscodeRequest::attemptId)
        .doesNotContainAnyElementsOf(previousAttempts);
  }

  @Test
  @DisplayName(
      "Should relocate the transcode when the requested segment is behind the encoder start")
  void shouldRelocateTheTranscodeWhenTheRequestedSegmentIsBehindTheEncoderStart() {
    var session = startedSession();
    // Move the encoder forward first: segment50 is far ahead of fresh output.
    lifecycle.ensurePositioned(session.getSessionId(), "segment50.ts");

    lifecycle.ensurePositioned(session.getSessionId(), "segment10.ts");

    // The encoder started at segment50 and will never produce segment10.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(3);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(60);
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(10);
  }

  @Test
  @DisplayName("Should relocate the transcode when the requested segment is far ahead of progress")
  void shouldRelocateTheTranscodeWhenTheRequestedSegmentIsFarAheadOfProgress() {
    var session = startedSession();

    lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts");

    // Nothing near segment100 has been produced; waiting would stall the player.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(2);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(600);
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(100);
  }

  @Test
  @DisplayName("Should wait when the requested segment is near the encoder start")
  void shouldWaitWhenTheRequestedSegmentIsNearTheEncoderStart() {
    var session = startedSession();
    var attemptBefore = session.getHandle().orElseThrow().attemptId();
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "segment2.ts");

    // The encoder started at segment0 and will reach segment2 shortly.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptBefore);
  }

  @Test
  @DisplayName("Should wait when the encoder is within the forward gap of the request")
  void shouldWaitWhenTheEncoderIsWithinTheForwardGapOfTheRequest() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment96.ts", new byte[] {1});
    var attemptBefore = session.getHandle().orElseThrow().attemptId();
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts");

    // segment96 exists, so the encoder is close behind the request.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptBefore);
  }

  @Test
  @DisplayName("Should not relocate when the requested segment already exists")
  void shouldNotRelocateWhenTheRequestedSegmentAlreadyExists() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment10.ts", new byte[] {1});
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "segment10.ts");

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should stop producers and mark active handles suspended when suspending")
  void shouldStopProducersAndMarkActiveHandlesSuspendedWhenSuspending() {
    var session = startedSession();

    lifecycle.suspend(session.getSessionId());

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should not resurrect a destroyed session when suspending it")
  void shouldNotResurrectDestroyedSessionWhenSuspending() {
    var session = startedSession();
    var sessionId = session.getSessionId();
    lifecycle.removeSession(sessionId);

    // The reaper iterates a snapshot, so it can still hold a reference to a destroyed session.
    lifecycle.suspend(session.getSessionId());

    assertThat(runtimeRegistry.findById(sessionId)).isEmpty();
  }

  @Test
  @DisplayName("Should preserve the attempt identity when suspending an active handle")
  void shouldPreserveTheAttemptIdentityWhenSuspendingAnActiveHandle() {
    var session = startedSession();
    var attemptId = session.getHandle().orElseThrow().attemptId();

    lifecycle.suspend(session.getSessionId());

    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptId);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  private ProducerLifecycleService.RecoveryResult recover(StreamSession session) {
    return lifecycle.recover(session.getSessionId(), StreamSession.defaultVariant(), "segment2.ts");
  }

  @Test
  @DisplayName(
      "Should install a fresh attempt at the requested offset when recovering a dead producer")
  void shouldInstallFreshAttemptAtRequestedOffsetWhenRecoveringDeadProducer() {
    var session = startedSession();
    var deadAttempt = session.getHandle().orElseThrow().attemptId();
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    var handle = session.getHandle().orElseThrow();
    assertThat(handle.status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(handle.attemptId()).isNotEqualTo(deadAttempt);
    assertThat(handle.startSequenceNumber()).isEqualTo(2);
    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.seekPosition()).isEqualTo(12);
    assertThat(request.startSequenceNumber()).isEqualTo(2);
    assertThat(request.attemptId()).isEqualTo(handle.attemptId());
  }

  @Test
  @DisplayName("Should stop and replace the producer when its startup budget expires")
  void shouldStopAndReplaceProducerWhenItsStartupBudgetExpires() {
    var session = startedSession();
    recover(session);
    clock.advance(Duration.ofSeconds(16));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
    assertThat(transcodeExecutor.getStoppedVariants())
        .containsExactly(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName("Should preserve the current attempt when its producer is healthy")
  void shouldPreserveCurrentAttemptWhenItsProducerIsHealthy() {
    var session = startedSession();
    var attempt = session.getHandle().orElseThrow().attemptId();

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attempt);
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
  }

  @Test
  @DisplayName("Should recover on an execution target when a suspended session cannot resume")
  void shouldRecoverOnExecutionTargetWhenSuspendedSessionCannotResume() {
    var session = startedSession();
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
  }

  @Test
  @DisplayName(
      "Should exhaust a suspended session when neither resume nor recovery can start a producer")
  void shouldExhaustSuspendedSessionWhenNeitherResumeNorRecoveryCanStartProducer() {
    var session = startedSession();
    lifecycle.suspend(session.getSessionId());
    transcodeExecutor.failUntargetedStarts();
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should retain the variant geometry when recovering one ABR producer")
  void shouldRetainVariantGeometryWhenRecoveringOneAbrProducer() {
    var session = startedAbrSession();
    transcodeExecutor.markDead(session.getSessionId(), "720p");

    var result = lifecycle.recover(session.getSessionId(), "720p", "720p/segment2.ts");

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.variantLabel()).isEqualTo("720p");
    assertThat(request.width()).isEqualTo(1280);
    assertThat(request.height()).isEqualTo(720);
    assertThat(request.bitrate()).isEqualTo(3_000_000L);
    assertThat(request.attemptId())
        .isEqualTo(session.getVariantHandle("720p").orElseThrow().attemptId());
  }

  @Test
  @DisplayName("Should report session gone when recovery follows destruction")
  void shouldReportSessionGoneWhenRecoveryFollowsDestruction() {
    var session = startedSession();
    lifecycle.removeSession(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.SESSION_GONE);
  }

  @Test
  @DisplayName("Should leave the producer alone when the requested segment already exists")
  void shouldLeaveProducerAloneWhenRequestedSegmentAlreadyExists() {
    var session = startedSession();
    var attempt = session.getHandle().orElseThrow().attemptId();
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {1});

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attempt);
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeStatus.class,
      names = {"STOPPED", "STARTING", "SEEKING"})
  @DisplayName("Should leave a planned transition alone when recovery observes its handle")
  void shouldLeavePlannedTransitionAloneWhenRecoveryObservesItsHandle(TranscodeStatus status) {
    var session = startedSession();
    var handle = session.getHandle().orElseThrow().withStatus(status);
    session.setHandle(handle);
    transcodeExecutor.markDead(session.getSessionId());

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle()).contains(handle);
    assertThat(transcodeExecutor.getStartedTargets()).isEmpty();
  }

  @Test
  @DisplayName("Should recover a suspended variant when its siblings keep the session live")
  void shouldRecoverSuspendedVariantWhenItsSiblingsKeepSessionLive() {
    var session = startedAbrSession();
    var sibling = session.getVariantHandle("720p").orElseThrow();
    var suspended =
        session.getVariantHandle("1080p").orElseThrow().withStatus(TranscodeStatus.SUSPENDED);
    session.setVariantHandle("1080p", suspended);
    transcodeExecutor.markDead(session.getSessionId(), "1080p");

    var result = lifecycle.recover(session.getSessionId(), "1080p", "1080p/segment2.ts");

    assertThat(result).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);
    assertThat(session.getVariantHandle("1080p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getVariantHandle("720p")).contains(sibling);
  }

  @Test
  @DisplayName("Should revive an exhausted variant when a new target becomes eligible")
  void shouldReviveExhaustedVariantWhenNewTargetBecomesEligible() {
    var session = startedSession();
    transcodeExecutor.markDead(session.getSessionId());
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);
    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);
    var newTarget = new ExecutionTargetId("new-worker");
    transcodeExecutor.setExecutionTargets(List.of(newTarget));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.WAITING);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(newTarget);
  }

  @Test
  @DisplayName("Should stop the final stalled producer when every target has been tried")
  void shouldStopFinalStalledProducerWhenEveryTargetHasBeenTried() {
    var session = startedSession();
    recover(session);
    clock.advance(Duration.ofSeconds(16));
    recover(session);
    clock.advance(Duration.ofSeconds(16));

    assertThat(recover(session)).isEqualTo(ProducerLifecycleService.RecoveryResult.EXHAUSTED);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isFalse();
    assertThat(transcodeExecutor.getStartedTargets()).containsExactly(ExecutionTargetId.LOCAL);
  }

  @Test
  @DisplayName("Should stop every producer under the session mutex when destroying")
  void shouldStopEveryProducerUnderTheSessionMutexWhenDestroying() {
    var session = startedSession();

    lifecycle.stopForDestroy(session.getSessionId());

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId(), StreamSession.defaultVariant()))
        .isFalse();
  }
}
