package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
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

@Tag("UnitTest")
@DisplayName("Producer Lifecycle Service Tests")
class ProducerLifecycleServiceTest {

  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;

  @BeforeEach
  void setUp() {
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .maxConcurrentTranscodes(3)
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .sessionTimeout(Duration.ofSeconds(60))
                    .build())
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(new MutexFactory<>())
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

  private void suspendHandle(StreamSession session) {
    session.setHandle(new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());
  }

  @Test
  @DisplayName("Should keep previously transcoded segments when relocating")
  void shouldKeepPreviouslyTranscodedSegmentsWhenRelocating() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment0.ts", new byte[] {1});

    lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts");

    // Segments are addressed on the absolute timeline, so earlier segments stay valid.
    assertThat(segmentStore.segmentExists(session.getSessionId(), "segment0.ts")).isTrue();
  }

  @Test
  @DisplayName("Should restart FFmpeg when segment is missing from suspended session")
  void shouldRestartFfmpegWhenSegmentIsMissingFromSuspendedSession() {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts");

    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
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
  }

  @ParameterizedTest(name = "{0} → startNumber={1}, seek={2}")
  @DisplayName("Should resume with correct start number when segment name encodes an index")
  @CsvSource({"segment5.ts, 5, 30", "segment12.m4s, 12, 72", "720p/segment3.ts, 3, 18"})
  void shouldResumeWithCorrectStartNumberWhenSegmentNameEncodesIndex(
      String segmentName, int startNumber, int seekPosition) {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), segmentName);

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(startNumber);
    assertThat(lastRequest.seekPosition()).isEqualTo(seekPosition);
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
  @DisplayName("Should resume at beginning when segment is first")
  void shouldResumeAtBeginningWhenSegmentIsFirst() {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), "segment0.ts");

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

    for (var label : variantLabels) {
      session.setVariantHandle(label, new TranscodeHandle(1L, TranscodeStatus.SUSPENDED));
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
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(600);
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(100);
  }

  @Test
  @DisplayName("Should wait when the requested segment is near the encoder start")
  void shouldWaitWhenTheRequestedSegmentIsNearTheEncoderStart() {
    var session = startedSession();
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "segment2.ts");

    // The encoder started at segment0 and will reach segment2 shortly.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
  }

  @Test
  @DisplayName("Should wait when the encoder is within the forward gap of the request")
  void shouldWaitWhenTheEncoderIsWithinTheForwardGapOfTheRequest() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment96.ts", new byte[] {1});
    var requestsBefore = transcodeExecutor.getStartedRequests().size();

    lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts");

    // segment96 exists, so the encoder is close behind the request.
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(requestsBefore);
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
  @DisplayName("Should resume at the absolute segment position when resuming")
  void shouldResumeAtTheAbsoluteSegmentPositionWhenResuming() {
    var session = startedSession();
    suspendHandle(session);

    lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts");

    // The timeline is absolute: segment5 always covers [30s, 36s).
    var lastRequest = transcodeExecutor.getStartedRequests().getLast();
    assertThat(lastRequest.seekPosition()).isEqualTo(30);
    assertThat(lastRequest.startSequenceNumber()).isEqualTo(5);
  }

  @Test
  @DisplayName("Should stop producers and mark active handles suspended when suspending")
  void shouldStopProducersAndMarkActiveHandlesSuspendedWhenSuspending() {
    var session = startedSession();

    lifecycle.suspend(session);

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should preserve the attempt identity when suspending an active handle")
  void shouldPreserveTheAttemptIdentityWhenSuspendingAnActiveHandle() {
    var session = startedSession();
    var attemptId = session.getHandle().orElseThrow().attemptId();

    lifecycle.suspend(session);

    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptId);
  }

  private ProducerLifecycleService.ReplaceProducerCommand.ReplaceProducerCommandBuilder
      replaceCommand(StreamSession session) {
    var handle = session.getHandle().orElse(null);
    return ProducerLifecycleService.ReplaceProducerCommand.builder()
        .sessionId(session.getSessionId())
        .variantLabel(StreamSession.defaultVariant())
        .segmentName("segment2.ts")
        .segmentIndex(2)
        .expectedAttemptId(handle == null ? null : handle.attemptId())
        .target(ExecutionTargetId.LOCAL);
  }

  @Test
  @DisplayName("Should install a fresh attempt at the requested offset when replacing a producer")
  void shouldInstallFreshAttemptAtTheRequestedOffsetWhenReplacingProducer() {
    var session = startedSession();
    var deadAttempt = session.getHandle().orElseThrow().attemptId();
    transcodeExecutor.markDead(session.getSessionId());

    var result = lifecycle.replaceProducer(replaceCommand(session).build());

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Replaced.class);
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
  @DisplayName("Should stop an alive producer before replacing it when the caller observed a stall")
  void shouldStopAliveProducerBeforeReplacingItWhenTheCallerObservedStall() {
    var session = startedSession();

    var result =
        lifecycle.replaceProducer(
            replaceCommand(session)
                .reason(ProducerLifecycleService.ReplacementReason.STALLED)
                .build());

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Replaced.class);
    assertThat(transcodeExecutor.getStoppedVariants())
        .contains(session.getSessionId() + "/" + StreamSession.defaultVariant());
  }

  @Test
  @DisplayName("Should supersede a death claim when the producer is actually running")
  void shouldSupersedeDeathClaimWhenTheProducerIsActuallyRunning() {
    var session = startedSession();
    var attemptBefore = session.getHandle().orElseThrow().attemptId();

    var result =
        lifecycle.replaceProducer(
            replaceCommand(session)
                .reason(ProducerLifecycleService.ReplacementReason.DEAD)
                .build());

    // A stale death observation — e.g. against another waiter's healthy replacement — must
    // re-observe, never kill.
    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Superseded.class);
    assertThat(transcodeExecutor.getStoppedVariants()).isEmpty();
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptBefore);
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isTrue();
  }

  @Test
  @DisplayName("Should replace a suspended handle when the caller's resume attempt failed")
  void shouldReplaceSuspendedHandleWhenTheCallersResumeAttemptFailed() {
    var session = startedSession();
    lifecycle.suspend(session);

    var result =
        lifecycle.replaceProducer(
            replaceCommand(session)
                .reason(ProducerLifecycleService.ReplacementReason.RESUME_FAILED)
                .build());

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Replaced.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should exhaust a suspended handle when the expected attempt matches")
  void shouldExhaustSuspendedHandleWhenTheExpectedAttemptMatches() {
    var session = startedSession();
    lifecycle.suspend(session);

    var result =
        lifecycle.markExhausted(
            session.getSessionId(),
            StreamSession.defaultVariant(),
            session.getHandle().orElseThrow().attemptId());

    // Reached only when nothing — not even a resume — can produce the segment.
    assertThat(result).isInstanceOf(ProducerLifecycleService.ExhaustResult.Exhausted.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should build the replacement from the variant's own geometry for ABR sessions")
  void shouldBuildReplacementFromTheVariantsOwnGeometryForAbrSessions() {
    var session = startedAbrSession();
    transcodeExecutor.markDead(session.getSessionId(), "720p");
    var command =
        replaceCommand(session)
            .variantLabel("720p")
            .segmentName("720p/segment2.ts")
            .expectedAttemptId(session.getVariantHandle("720p").orElseThrow().attemptId())
            .build();

    var result = lifecycle.replaceProducer(command);

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Replaced.class);
    var request = transcodeExecutor.getStartedRequests().getLast();
    assertThat(request.variantLabel()).isEqualTo("720p");
    assertThat(request.width()).isEqualTo(1280);
    assertThat(request.height()).isEqualTo(720);
    assertThat(request.bitrate()).isEqualTo(3_000_000L);
  }

  @Test
  @DisplayName("Should report session gone when replacing in a destroyed session")
  void shouldReportSessionGoneWhenReplacingInDestroyedSession() {
    var session = startedSession();
    var command = replaceCommand(session).build();
    runtimeRegistry.removeById(session.getSessionId());

    var result = lifecycle.replaceProducer(command);

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.SessionGone.class);
  }

  @Test
  @DisplayName("Should report superseded when the requested segment already exists")
  void shouldReportSupersededWhenTheRequestedSegmentAlreadyExists() {
    var session = startedSession();
    segmentStore.addSegment(session.getSessionId(), "segment2.ts", new byte[] {1});

    var result = lifecycle.replaceProducer(replaceCommand(session).build());

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Superseded.class);
  }

  @Test
  @DisplayName("Should report superseded when the handle carries a different attempt")
  void shouldReportSupersededWhenTheHandleCarriesDifferentAttempt() {
    var session = startedSession();
    var command = replaceCommand(session).expectedAttemptId(UUID.randomUUID()).build();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var result = lifecycle.replaceProducer(command);

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Superseded.class);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName("Should report superseded when a planned suspension fenced the replacement")
  void shouldReportSupersededWhenPlannedSuspensionFencedTheReplacement() {
    var session = startedSession();
    var command = replaceCommand(session).build();
    lifecycle.suspend(session);
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    var result = lifecycle.replaceProducer(command);

    // The suspension kept the attempt id; the status fence alone must reject the replacement.
    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Superseded.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName("Should replace a failed handle with a matching attempt for the new-target reset")
  void shouldReplaceFailedHandleWithMatchingAttemptForTheNewTargetReset() {
    var session = startedSession();
    var exhausted =
        lifecycle.markExhausted(
            session.getSessionId(),
            StreamSession.defaultVariant(),
            session.getHandle().orElseThrow().attemptId());
    assertThat(exhausted).isInstanceOf(ProducerLifecycleService.ExhaustResult.Exhausted.class);

    var result = lifecycle.replaceProducer(replaceCommand(session).build());

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Replaced.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should report refused when the execution target cannot start the producer")
  void shouldReportRefusedWhenTheExecutionTargetCannotStartTheProducer() {
    var session = startedSession();
    transcodeExecutor.markDead(session.getSessionId());
    transcodeExecutor.refuseTarget(ExecutionTargetId.LOCAL);

    var result = lifecycle.replaceProducer(replaceCommand(session).build());

    assertThat(result).isInstanceOf(ProducerLifecycleService.ReplaceResult.Refused.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should mark the variant failed and stop its live producer when exhausting")
  void shouldMarkTheVariantFailedAndStopItsLiveProducerWhenExhausting() {
    var session = startedSession();

    var result =
        lifecycle.markExhausted(
            session.getSessionId(),
            StreamSession.defaultVariant(),
            session.getHandle().orElseThrow().attemptId());

    assertThat(result).isInstanceOf(ProducerLifecycleService.ExhaustResult.Exhausted.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
    // FAILED promises "no producer": the final stalled-but-alive attempt must not keep running.
    assertThat(transcodeExecutor.getStoppedVariants())
        .contains(session.getSessionId() + "/" + StreamSession.defaultVariant());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isFalse();
  }

  @Test
  @DisplayName("Should not exhaust when a planned restart superseded the attempt")
  void shouldNotExhaustWhenPlannedRestartSupersededTheAttempt() {
    var session = startedSession();
    var staleAttempt = session.getHandle().orElseThrow().attemptId();
    lifecycle.suspend(session);
    lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts");

    var result =
        lifecycle.markExhausted(
            session.getSessionId(), StreamSession.defaultVariant(), staleAttempt);

    // The resumed producer must never inherit a stale 503.
    assertThat(result).isInstanceOf(ProducerLifecycleService.ExhaustResult.Superseded.class);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should stop every producer under the session mutex when destroying")
  void shouldStopEveryProducerUnderTheSessionMutexWhenDestroying() {
    var session = startedSession();

    lifecycle.stopForDestroy(session.getSessionId());

    assertThat(transcodeExecutor.getStopped()).contains(session.getSessionId());
    assertThat(transcodeExecutor.isRunning(session.getSessionId())).isFalse();
  }
}
