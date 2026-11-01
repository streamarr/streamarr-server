package com.streamarr.server.services.streaming;

import static com.streamarr.server.domain.streaming.ProbedCodecFamily.H264;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.HEVC;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ShortCopy;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.PlaybackSessionClosedException;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.CopiedVariantRanShortEvent;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Playback Session Service Tests")
class PlaybackSessionServiceTest {
  private final ChildStreams streams = new ChildStreams();
  private final FakePlaybackAuthorityGate authority = new FakePlaybackAuthorityGate();
  private final MutableClock clock = new MutableClock();
  private final PlaybackSessionService service =
      PlaybackSessionService.builder()
          .streamingService(streams)
          .authorityGate(authority)
          .executor(Runnable::run)
          .maximumFormatAttempts(2)
          .sessionRetention(Duration.ofHours(24))
          .clock(clock)
          .build();

  @Test
  @DisplayName("Should count the initial format attempt when playback starts")
  void shouldCountTheInitialFormatAttemptWhenPlaybackStarts() {
    var command = startCommand().build();
    var snapshot = accepted(service.start(command));

    assertThat(snapshot.formatAttemptCount()).isEqualTo(1);
    assertThat(snapshot.maximumFormatAttempts()).isEqualTo(2);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getMediaFileId()).isEqualTo(command.mediaFileId());
              assertThat(ready.streamSessionId()).isNotEqualTo(snapshot.playbackSessionId());
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
  }

  // ADR 0052: an encode that Original reaches plays in Auto, and the playback session says so.
  @Test
  @DisplayName("Should play the encode in Auto when Original cannot copy the source")
  void shouldPlayTheEncodeInAutoWhenOriginalCannotCopyTheSource() {
    streams.copiesTheSource = false;
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();

    var snapshot = accepted(service.start(command));

    assertThat(snapshot.requestedChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(snapshot.formatChange()).contains(PlaybackFormatChange.STARTED_WITH_ENCODE);
    assertThat(snapshot.formatAttemptCount()).isEqualTo(1);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                    .isFalse());
  }

  @Test
  @DisplayName("Should recover as Auto does when the encode that Original started with fails")
  void shouldRecoverAsAutoDoesWhenTheEncodeThatOriginalStartedWithFails() {
    streams.copiesTheSource = false;
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var initial = accepted(service.start(command));

    var recovered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(recovered.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(recovered.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(recovered.formatChange()).contains(PlaybackFormatChange.STARTED_WITH_ENCODE);
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            replacement -> {
              assertThat(replacement.options().quality()).isEqualTo(VideoQuality.AUTO);
              assertThat(replacement.excludedEncodeCodecFamilies()).containsExactly("h264");
            });
  }

  @Test
  @DisplayName("Should keep Original without a format change when Original copies the source")
  void shouldKeepOriginalWithoutAFormatChangeWhenOriginalCopiesTheSource() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();

    var snapshot = accepted(service.start(command));

    assertThat(snapshot.requestedChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(snapshot.formatChange()).isEmpty();
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                    .isTrue());
  }

  // A fixed quality larger than the source would not enlarge it, so it is not offered. The 1440p
  // tier has no fixed quality, and a 1080×1920 portrait source fits the 1080p tier's box.
  @ParameterizedTest
  @CsvSource({
    "3840, 2160, 'ORIGINAL,AUTO,UHD_4K,FULL_HD_1080P,HIGH_720P,MEDIUM_480P,LOW_360P'",
    "2560, 1440, 'ORIGINAL,AUTO,FULL_HD_1080P,HIGH_720P,MEDIUM_480P,LOW_360P'",
    "1920, 1080, 'ORIGINAL,AUTO,FULL_HD_1080P,HIGH_720P,MEDIUM_480P,LOW_360P'",
    "1920, 1088, 'ORIGINAL,AUTO,FULL_HD_1080P,HIGH_720P,MEDIUM_480P,LOW_360P'",
    "1080, 1920, 'ORIGINAL,AUTO,FULL_HD_1080P,HIGH_720P,MEDIUM_480P,LOW_360P'",
    "1280, 720, 'ORIGINAL,AUTO,HIGH_720P,MEDIUM_480P,LOW_360P'",
    "720, 480, 'ORIGINAL,AUTO,MEDIUM_480P,LOW_360P'",
    "320, 240, 'ORIGINAL,AUTO,LOW_360P'"
  })
  @DisplayName(
      "Should offer Original, Auto and each fixed quality up to the source's tier when playback"
          + " starts")
  void shouldOfferOriginalAutoAndEachFixedQualityUpToTheSourcesTierWhenPlaybackStarts(
      int width, int height, String expected) {
    streams.sourceProbe = defaultProbeBuilder().width(width).height(height).build();
    var command = startCommand().build();

    var snapshot = accepted(service.start(command));

    assertThat(snapshot.offeredChoices())
        .containsExactlyElementsOf(
            Arrays.stream(expected.split(",")).map(VideoQuality::valueOf).toList());
    var recovered = service.reportFormatFailure(report(snapshot, command).build()).orElseThrow();
    assertThat(recovered.offeredChoices()).isEqualTo(snapshot.offeredChoices());
  }

  // A fixed quality's own encode is the output that the viewer asked for.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "LOW_360P", "MEDIUM_480P", "HIGH_720P", "FULL_HD_1080P", "UHD_4K"})
  @DisplayName(
      "Should keep the playback choice without a format change when Auto or a fixed"
          + " quality starts with an encode")
  void shouldKeepThePlaybackChoiceWithoutAFormatChangeWhenAutoOrAFixedQualityStartsWithAnEncode(
      VideoQuality quality) {
    streams.copiesTheSource = false;
    var command = startCommand().options(qualityOptions(quality)).build();

    var snapshot = accepted(service.start(command));

    assertThat(snapshot.requestedChoice()).isEqualTo(quality);
    assertThat(snapshot.playbackChoice()).isEqualTo(quality);
    assertThat(snapshot.formatChange()).isEmpty();
  }

  @Test
  @DisplayName("Should replace the copy when its mismatch arrives before initial creation returns")
  void shouldReplaceTheCopyWhenItsMismatchArrivesBeforeInitialCreationReturns() {
    var initialId = new AtomicReference<UUID>();
    streams.duringNextCreation =
        command -> {
          initialId.set(command.streamSessionId());
          service.onVideoCopyOutputMismatch(
              new VideoCopyOutputMismatchEvent(
                  command.streamSessionId(), UUID.randomUUID(), "default"));
        };
    var command = startCommand().build();

    var snapshot = accepted(service.start(command));

    assertThat(snapshot.formatAttemptCount()).isEqualTo(2);
    assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(snapshot.formatChange()).isEmpty();
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.streamSessionId()).isNotEqualTo(initialId.get());
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(ready.session().getOptions()).isEqualTo(command.options());
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
  }

  @ParameterizedTest
  @CsvSource({
    "AUTO, 1",
    "AUTO, 2",
    "AUTO, 3",
    "AUTO, 4",
    "ORIGINAL, 1",
    "ORIGINAL, 2",
    "ORIGINAL, 4"
  })
  @DisplayName(
      "Should bound total format attempts when Auto or Original reports repeated format failures")
  void shouldBoundTotalFormatAttemptsWhenAutoOrOriginalReportsRepeatedFormatFailures(
      VideoQuality quality, int maximum) {
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(Runnable::run)
            .maximumFormatAttempts(maximum)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var snapshot = accepted(playback.start(command));
    for (var attempt = 1; attempt <= maximum; attempt++) {
      snapshot = playback.reportFormatFailure(report(snapshot, command).build()).orElseThrow();
    }

    assertThat(snapshot.formatAttemptCount()).isEqualTo(maximum);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed ->
                assertThat(failed.reason())
                    .isEqualTo(new PlaybackSessionFailure.AttemptsExhausted()));
    assertThat(streams.getAllSessions()).isEmpty();
    assertThat(accepted(playback.start(command)).formatAttemptCount()).isEqualTo(1);
  }

  // ADR 0052: a fixed quality's failed HLS copy falls back to an encode at the same quality.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"AUTO", "ORIGINAL"})
  @DisplayName("Should encode at the same fixed quality when its copy mismatches")
  void shouldEncodeAtTheSameFixedQualityWhenItsCopyMismatches(VideoQuality quality) {
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(service.start(command));

    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));

    assertThatTheFixedQualityPlaysAnEncodeAfterItsCopyFailed(
        service.access(request(initial, command)).orElseThrow(), quality);
  }

  @ParameterizedTest
  @CsvSource({
    "AUTO,OUTPUT_MISMATCH",
    "AUTO,INVALID_SPECIFICATION",
    "HIGH_720P,OUTPUT_MISMATCH",
    "ORIGINAL,INVALID_SPECIFICATION"
  })
  @DisplayName("Should fail playback without a new format attempt when a job failure is final")
  void shouldFailPlaybackWithoutNewFormatAttemptWhenJobFailureIsFinal(
      VideoQuality quality, TranscodeFailure failure) {
    var command =
        startCommand().options(StreamingOptions.builder().quality(quality).build()).build();
    var snapshot = accepted(service.start(command));
    var failedId = snapshot.currentFormatAttempt().streamSessionId();

    service.onTerminalTranscodeFailure(terminalFailure(failedId, failure));

    var failed = service.access(request(snapshot, command)).orElseThrow();
    assertThat(failed.formatAttemptCount()).isEqualTo(1);
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(new PlaybackFormatAttempt.Failed(failedId, playbackFailureOf(failure)));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore a final job failure when it names a retired stream session")
  void shouldIgnoreFinalJobFailureWhenItNamesRetiredStreamSession() {
    var command = startCommand().build();
    var snapshot = accepted(service.start(command));
    var retiredId = snapshot.currentFormatAttempt().streamSessionId();
    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(retiredId, UUID.randomUUID(), "default"));

    service.onTerminalTranscodeFailure(
        terminalFailure(retiredId, TranscodeFailure.INVALID_SPECIFICATION));

    var current = service.access(request(snapshot, command)).orElseThrow();
    assertThat(current.formatAttemptCount()).isEqualTo(2);
    assertThat(current.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
  }

  @Test
  @DisplayName("Should ignore a final job failure when no playback session owns the stream")
  void shouldIgnoreFinalJobFailureWhenNoPlaybackSessionOwnsTheStream() {
    var event = terminalFailure(UUID.randomUUID(), TranscodeFailure.OUTPUT_MISMATCH);

    service.onTerminalTranscodeFailure(event);

    assertThat(streams.getAllSessions()).isEmpty();
  }

  private static TerminalTranscodeFailureEvent terminalFailure(
      UUID streamSessionId, TranscodeFailure failure) {
    return TerminalTranscodeFailureEvent.builder()
        .streamSessionId(streamSessionId)
        .jobAttemptId(UUID.randomUUID())
        .variantLabel("default")
        .failure(failure)
        .build();
  }

  private static PlaybackSessionFailure playbackFailureOf(TranscodeFailure failure) {
    return switch (failure) {
      case OUTPUT_MISMATCH -> new PlaybackSessionFailure.OutputMismatch();
      case INVALID_SPECIFICATION -> new PlaybackSessionFailure.InvalidSpecification();
    };
  }

  // A worker's copy mismatch names a copy even before the format attempt that it fails is ready.
  @Test
  @DisplayName(
      "Should return a pending encode at the fixed quality when its copy mismatches before"
          + " creation returns")
  void shouldReturnAPendingEncodeAtTheFixedQualityWhenItsCopyMismatchesBeforeCreationReturns() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback = serviceRunning(work::add).maximumFormatAttempts(2).build();
    streams.duringNextCreation =
        child ->
            playback.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(
                    child.streamSessionId(), UUID.randomUUID(), "default"));
    var command = startCommand().options(qualityOptions(VideoQuality.HIGH_720P)).build();

    var pending = accepted(playback.start(command));

    assertThat(pending.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(pending.formatAttemptCount()).isEqualTo(2);
    assertThat(pending.playbackChoice()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(pending.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    drain(work);
    assertThatTheFixedQualityPlaysAnEncodeAfterItsCopyFailed(
        playback.access(request(pending, command)).orElseThrow(), VideoQuality.HIGH_720P);
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"AUTO", "ORIGINAL"})
  @DisplayName("Should encode at the same fixed quality when its copy fails")
  void shouldEncodeAtTheSameFixedQualityWhenItsCopyFails(VideoQuality quality) {
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(service.start(command));

    var recovered =
        service
            .reportFormatFailure(
                report(initial, command).reason(PlaybackFormatFailure.UNSUPPORTED_FORMAT).build())
            .orElseThrow();

    assertThatTheFixedQualityPlaysAnEncodeAfterItsCopyFailed(recovered, quality);
    assertThat(streams.getAllSessions())
        .singleElement()
        .extracting(StreamSession::getSessionId)
        .isEqualTo(recovered.currentFormatAttempt().streamSessionId());
  }

  // The fixed quality's own encode is the output that the viewer asked for, so its failure ends
  // playback with the client's reason while attempts remain.
  @Test
  @DisplayName(
      "Should end with the typed error when the encode after the fixed quality's copy fails")
  void shouldEndWithTheTypedErrorWhenTheEncodeAfterTheFixedQualitysCopyFails() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(VideoQuality.HIGH_720P)).build();
    var copy = accepted(playback.start(command));
    var encode = playback.reportFormatFailure(report(copy, command).build()).orElseThrow();

    var failed =
        playback
            .reportFormatFailure(
                report(encode, command).reason(PlaybackFormatFailure.UNSUPPORTED_FORMAT).build())
            .orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(2);
    assertThat(failed.playbackChoice()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(failed.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                encode.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.FixedFormatFailed(
                    PlaybackFormatFailure.UNSUPPORTED_FORMAT)));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should end with the typed error when the fixed quality starts with an encode that fails")
  void shouldEndWithTheTypedErrorWhenTheFixedQualityStartsWithAnEncodeThatFails() {
    streams.copiesTheSource = false;
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(VideoQuality.HIGH_720P)).build();
    var encode = accepted(playback.start(command));

    var failed =
        playback
            .reportFormatFailure(
                report(encode, command).reason(PlaybackFormatFailure.UNSUPPORTED_FORMAT).build())
            .orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(1);
    assertThat(failed.formatChange()).isEmpty();
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                encode.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.FixedFormatFailed(
                    PlaybackFormatFailure.UNSUPPORTED_FORMAT)));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should end with the typed error when no encode can replace the fixed quality's copy")
  void shouldEndWithTheTypedErrorWhenNoEncodeCanReplaceTheFixedQualitysCopy() {
    var command = startCommand().options(qualityOptions(VideoQuality.HIGH_720P)).build();
    var initial = accepted(service.start(command));
    streams.rejection = new CreateStreamSessionRejection.NoEligibleVideoFormat();

    var failed = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(2);
    assertThat(failed.playbackChoice()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(new PlaybackSessionFailure.CreationRejected(streams.rejection)));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  // No encode was reached, so nothing changed for the client to announce.
  @Test
  @DisplayName(
      "Should end with attempts exhausted when the fixed quality's copy fails with no attempt"
          + " left")
  void shouldEndWithAttemptsExhaustedWhenTheFixedQualitysCopyFailsWithNoAttemptLeft() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(1).build();
    var command = startCommand().options(qualityOptions(VideoQuality.HIGH_720P)).build();
    var initial = accepted(playback.start(command));

    var failed = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(1);
    assertThat(failed.playbackChoice()).isEqualTo(VideoQuality.HIGH_720P);
    assertThat(failed.formatChange()).isEmpty();
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                initial.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.AttemptsExhausted()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  // ADR 0052: Original's failed HLS copy falls back to an encode, which plays in Auto.
  @ParameterizedTest
  @EnumSource(PlaybackFormatFailure.class)
  @DisplayName(
      "Should start an encode in Auto when Original reports a format failure during its copy")
  void shouldStartAnEncodeInAutoWhenOriginalReportsAFormatFailureDuringItsCopy(
      PlaybackFormatFailure reason) {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var initial = accepted(service.start(command));

    var recovered =
        service.reportFormatFailure(report(initial, command).reason(reason).build()).orElseThrow();

    assertThatOriginalPlaysAnEncodeInAutoAfterItsCopyFailed(recovered);
    assertThat(streams.getAllSessions())
        .singleElement()
        .extracting(StreamSession::getSessionId)
        .isEqualTo(recovered.currentFormatAttempt().streamSessionId());
  }

  @Test
  @DisplayName("Should start an encode in Auto when Original's copy mismatches")
  void shouldStartAnEncodeInAutoWhenOriginalsCopyMismatches() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var initial = accepted(service.start(command));

    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));

    assertThatOriginalPlaysAnEncodeInAutoAfterItsCopyFailed(
        service.access(request(initial, command)).orElseThrow());
  }

  // The playback session says that Original plays in Auto while the encode is still pending.
  @Test
  @DisplayName(
      "Should return a pending encode in Auto when Original's copy mismatches before creation"
          + " returns")
  void shouldReturnAPendingEncodeInAutoWhenOriginalsCopyMismatchesBeforeCreationReturns() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback = serviceRunning(work::add).maximumFormatAttempts(2).build();
    streams.duringNextCreation =
        child ->
            playback.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(
                    child.streamSessionId(), UUID.randomUUID(), "default"));
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();

    var pending = accepted(playback.start(command));

    assertThat(pending.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(pending.formatAttemptCount()).isEqualTo(2);
    assertThat(pending.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(pending.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    drain(work);
    assertThatOriginalPlaysAnEncodeInAutoAfterItsCopyFailed(
        playback.access(request(pending, command)).orElseThrow());
  }

  @Test
  @DisplayName("Should recover as Auto does when the encode after Original's copy fails")
  void shouldRecoverAsAutoDoesWhenTheEncodeAfterOriginalsCopyFails() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(3).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var initial = accepted(playback.start(command));
    var encode = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();

    var recovered = playback.reportFormatFailure(report(encode, command).build()).orElseThrow();

    assertThat(recovered.formatAttemptCount()).isEqualTo(3);
    assertThat(recovered.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(recovered.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(recovered.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    assertThat(streams.createdCommands.getLast().excludedEncodeCodecFamilies())
        .containsExactly("h264");
  }

  @Test
  @DisplayName("Should end with the typed error when no encode can replace Original's failed copy")
  void shouldEndWithTheTypedErrorWhenNoEncodeCanReplaceOriginalsFailedCopy() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var initial = accepted(service.start(command));
    streams.rejection = new CreateStreamSessionRejection.NoEligibleVideoFormat();

    var failed = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(2);
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(new PlaybackSessionFailure.CreationRejected(streams.rejection)));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  // ADR 0052: a copy that ran short after every source stream ended ends playback normally, and
  // that end wins over every failure in its stream session.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "ORIGINAL", "HIGH_720P"})
  @DisplayName(
      "Should answer a format failure report with the normal end when the copy ended normally")
  void shouldAnswerAFormatFailureReportWithTheNormalEndWhenTheCopyEndedNormally(
      VideoQuality quality) {
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(service.start(command));
    var copy = endNormally(initial);

    var answered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThatTheCopyStillPlaysToItsNormalEnd(answered, copy, quality);
  }

  @ParameterizedTest
  @MethodSource("workerFailures")
  @DisplayName("Should keep the normal end when a worker failure follows it")
  void shouldKeepTheNormalEndWhenAWorkerFailureFollowsIt(
      BiConsumer<PlaybackSessionService, UUID> workerFailure) {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var initial = accepted(service.start(command));
    var copy = endNormally(initial);

    workerFailure.accept(service, copy.getSessionId());

    assertThatTheCopyStillPlaysToItsNormalEnd(
        service.access(request(initial, command)).orElseThrow(), copy, VideoQuality.ORIGINAL);
  }

  private static Stream<Arguments> workerFailures() {
    return Stream.of(
        Arguments.of(
            Named.<BiConsumer<PlaybackSessionService, UUID>>of(
                "copy output mismatch",
                (playback, streamId) ->
                    playback.onVideoCopyOutputMismatch(
                        new VideoCopyOutputMismatchEvent(streamId, UUID.randomUUID(), "default")))),
        Arguments.of(
            Named.<BiConsumer<PlaybackSessionService, UUID>>of(
                "invalid specification",
                (playback, streamId) ->
                    playback.onTerminalTranscodeFailure(
                        terminalFailure(streamId, TranscodeFailure.INVALID_SPECIFICATION)))));
  }

  private static StreamSession endNormally(PlaybackSessionSnapshot snapshot) {
    var copy = ((PlaybackFormatAttempt.Ready) snapshot.currentFormatAttempt()).session();
    copy.markShortCopy(new ShortCopy(3, Duration.ofSeconds(24), true));
    return copy;
  }

  private void assertThatTheCopyStillPlaysToItsNormalEnd(
      PlaybackSessionSnapshot snapshot, StreamSession copy, VideoQuality quality) {
    assertThat(snapshot.formatAttemptCount()).isEqualTo(1);
    assertThat(snapshot.playbackChoice()).isEqualTo(quality);
    assertThat(snapshot.formatChange()).isEmpty();
    assertThat(snapshot.currentFormatAttempt()).isEqualTo(new PlaybackFormatAttempt.Ready(copy));
    assertThat(copy.getNormalEnd()).contains(Duration.ofSeconds(24));
    assertThat(streams.getAllSessions()).containsExactly(copy);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  @DisplayName("Should reject the total attempt limit when it is not positive")
  void shouldRejectTheTotalAttemptLimitWhenItIsNotPositive(int maximum) {
    var builder =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(Runnable::run)
            .maximumFormatAttempts(maximum)
            .sessionRetention(Duration.ofHours(24));

    assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
  }

  // Auto's recoveries are not announced; Original's failed copy changes to Auto, and a fixed
  // quality's failed copy keeps the fixed quality (ADR 0052).
  @ParameterizedTest
  @MethodSource("racingCopyFailures")
  @DisplayName("Should reserve one replacement when client and worker failures race")
  void shouldReserveOneReplacementWhenClientAndWorkerFailuresRace(
      VideoQuality quality,
      VideoQuality expectedChoice,
      PlaybackFormatChange expectedChange,
      BiConsumer<PlaybackSessionService, UUID> workerFailure)
      throws Exception {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(3)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(playback.start(command));
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var client =
          threads.submit(
              () -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return playback.reportFormatFailure(report(initial, command).build());
              });
      var worker =
          threads.submit(
              () -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                workerFailure.accept(playback, initial.currentFormatAttempt().streamSessionId());
                return true;
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      client.get(5, TimeUnit.SECONDS);
      worker.get(5, TimeUnit.SECONDS);
    }

    var pending = playback.access(request(initial, command)).orElseThrow();
    assertThat(pending.formatAttemptCount()).isEqualTo(2);
    assertThat(pending.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(pending.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(pending.formatChange()).isEqualTo(Optional.ofNullable(expectedChange));
    assertThat(playback.reportFormatFailure(report(pending, command).build())).contains(pending);
    drain(work);
    var result = playback.access(request(initial, command)).orElseThrow();
    assertThat(result.formatAttemptCount()).isEqualTo(2);
    assertThat(result.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(playback.reportFormatFailure(report(initial, command).build())).contains(result);
    playback.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));
    assertThat(playback.access(request(initial, command))).contains(result);
    assertThat(streams.getActiveSessionCount()).isEqualTo(1);
  }

  private static Stream<Arguments> racingCopyFailures() {
    var choices =
        List.of(
            Arguments.of(VideoQuality.AUTO, VideoQuality.AUTO, null),
            Arguments.of(
                VideoQuality.ORIGINAL, VideoQuality.AUTO, PlaybackFormatChange.COPY_FAILED),
            Arguments.of(
                VideoQuality.HIGH_720P, VideoQuality.HIGH_720P, PlaybackFormatChange.COPY_FAILED));
    return choices.stream()
        .flatMap(
            choice ->
                copyFailures()
                    .map(
                        failure ->
                            Arguments.of(
                                choice.get()[0], choice.get()[1], choice.get()[2], failure)));
  }

  // The worker's two failures of a copy that every playback choice falls back from.
  private static Stream<Named<BiConsumer<PlaybackSessionService, UUID>>> copyFailures() {
    return Stream.of(
        Named.of(
            "copy output mismatch",
            (playback, streamId) ->
                playback.onVideoCopyOutputMismatch(
                    new VideoCopyOutputMismatchEvent(streamId, UUID.randomUUID(), "default"))),
        Named.of(
            "copy that runs short while a source stream still has media",
            (playback, streamId) -> playback.onCopiedVariantRanShort(ranShort(streamId))));
  }

  private static CopiedVariantRanShortEvent ranShort(UUID streamSessionId) {
    return CopiedVariantRanShortEvent.builder()
        .streamSessionId(streamSessionId)
        .jobAttemptId(UUID.randomUUID())
        .variantLabel("default")
        .build();
  }

  // ADR 0052: a short copy that a source stream outlasts falls back as every failed copy does.
  @ParameterizedTest
  @CsvSource({"AUTO, AUTO,", "ORIGINAL, AUTO, COPY_FAILED", "HIGH_720P, HIGH_720P, COPY_FAILED"})
  @DisplayName(
      "Should fall back to an encode when the copy runs short while a source stream still has media")
  void shouldFallBackToAnEncodeWhenTheCopyRunsShortWhileASourceStreamStillHasMedia(
      VideoQuality quality, VideoQuality expectedChoice, PlaybackFormatChange expectedChange) {
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(service.start(command));

    service.onCopiedVariantRanShort(ranShort(initial.currentFormatAttempt().streamSessionId()));

    var recovered = service.access(request(initial, command)).orElseThrow();
    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(recovered.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(recovered.formatChange()).isEqualTo(Optional.ofNullable(expectedChange));
    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
  }

  @Test
  @DisplayName("Should refuse playback creation when live authority is denied")
  void shouldRefusePlaybackCreationWhenLiveAuthorityIsDenied() {
    authority.deny();
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse pending status and reports when live authority is revoked")
  void shouldRefusePendingStatusAndReportsWhenLiveAuthorityIsRevoked() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    var pending = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();
    authority.deny();
    var pendingRequest = request(initial, command);
    var pendingReport = report(pending, command).build();
    assertThatThrownBy(() -> playback.access(pendingRequest))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> playback.reportFormatFailure(pendingReport))
        .isInstanceOf(AuthenticationRequiredException.class);
    drain(work);
    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"authSessionId", "accountId", "householdId"})
  @DisplayName("Should refuse another authority when its profile matches the playback owner")
  void shouldRefuseAnotherAuthorityWhenItsProfileMatchesThePlaybackOwner(String changedField) {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var owner = command.identity().playbackAuthority();
    var foreignAuthority =
        defaultPlaybackAuthorityBuilder()
            .authSessionId(owner.authSessionId())
            .accountId(owner.accountId())
            .householdId(owner.householdId())
            .profileId(owner.profileId());
    var foreign =
        identityFor(
            switch (changedField) {
              case "authSessionId" -> foreignAuthority.authSessionId(UUID.randomUUID()).build();
              case "accountId" -> foreignAuthority.accountId(UUID.randomUUID()).build();
              case "householdId" -> foreignAuthority.householdId(UUID.randomUUID()).build();
              default -> throw new IllegalArgumentException(changedField);
            });
    var foreignRequest =
        PlaybackSessionRequest.builder()
            .playbackSessionId(initial.playbackSessionId())
            .identity(foreign)
            .build();
    var foreignReport = report(initial, command).identity(foreign).build();
    assertThat(service.access(foreignRequest)).isEmpty();
    assertThat(service.reportFormatFailure(foreignReport)).isEmpty();
    service.close(foreignRequest);

    assertThat(service.access(request(initial, command))).contains(initial);
    assertThat(streams.getActiveSessionCount()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({
    "stream,true",
    "stream,false",
    "media,true",
    "media,false",
    "shutdown,true",
    "shutdown,false"
  })
  @DisplayName("Should discard late creation when its logical playback closes during startup")
  void shouldDiscardLateCreationWhenItsLogicalPlaybackClosesDuringStartup(
      String closing, boolean beforeRegistration) {
    Consumer<CreateStreamSessionCommand> close =
        command -> {
          switch (closing) {
            case "stream" -> service.closeOwningStream(command.streamSessionId());
            case "media" -> service.closeMediaFiles(Set.of(command.mediaFileId()));
            case "shutdown" -> service.closeAll();
            default -> throw new AssertionError(closing);
          }
        };
    if (beforeRegistration) {
      streams.beforeNextCreation = close;
    } else {
      streams.duringNextCreation = close;
    }

    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isInstanceOf(PlaybackSessionClosedException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should keep the current replacement when cleanup names its retired child")
  void shouldKeepTheCurrentReplacementWhenCleanupNamesItsRetiredChild() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    var pending = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();
    playback.closeOwningStream(initial.currentFormatAttempt().streamSessionId());
    assertThat(playback.access(request(initial, command))).contains(pending);
    playback.close(request(initial, command));
    drain(work);
    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should settle a terminal failure when replacement scheduling is rejected")
  void shouldSettleATerminalFailureWhenReplacementSchedulingIsRejected() {
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(
                _ -> {
                  throw new RejectedExecutionException();
                })
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    var result = playback.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(result.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed ->
                assertThat(failed.reason()).isEqualTo(new PlaybackSessionFailure.StartupFailed()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should discard a dispatched child when initial creation throws")
  void shouldDiscardADispatchedChildWhenInitialCreationThrows() {
    streams.duringNextCreation =
        _ -> {
          throw new IllegalStateException("fixture failure");
        };
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start)).isInstanceOf(IllegalStateException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should settle a terminal failure when replacement creation throws after dispatch")
  void shouldSettleATerminalFailureWhenReplacementCreationThrowsAfterDispatch() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.duringNextCreation =
        _ -> {
          throw new IllegalStateException("fixture failure");
        };
    var result = service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(result.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed ->
                assertThat(failed.reason()).isEqualTo(new PlaybackSessionFailure.StartupFailed()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should expire a terminal playback when it has no child stream")
  void shouldExpireATerminalPlaybackWhenItHasNoChildStream() {
    streams.copiesTheSource = false;
    var command = startCommand().options(qualityOptions(VideoQuality.HIGH_720P)).build();
    var initial = accepted(service.start(command));
    service.reportFormatFailure(report(initial, command).build());
    clock.advance(Duration.ofHours(25));
    service.reapExpired(clock.instant());
    assertThat(service.access(request(initial, command))).isEmpty();
  }

  @Test
  @DisplayName("Should reject new playback when shutdown has closed registration")
  void shouldRejectNewPlaybackWhenShutdownHasClosedRegistration() {
    service.closeAll();
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isInstanceOf(PlaybackSessionClosedException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should replay the initial capability declaration when a replacement format attempt starts")
  void shouldReplayTheInitialCapabilityDeclarationWhenAReplacementFormatAttemptStarts() {
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().build(), hevcEntryBuilder().build()))
            .build();
    var command = startCommand().capabilityDeclaration(declaration).build();
    var initial = accepted(service.start(command));

    service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(streams.createdCommands)
        .extracting(CreateStreamSessionCommand::capabilityDeclaration)
        .containsExactly(declaration, declaration);
  }

  @Test
  @DisplayName("Should close owned streams when queued recovery cannot check authority")
  void shouldCloseOwnedStreamsWhenQueuedRecoveryCannotCheckAuthority() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.reportFormatFailure(report(initial, command).build());
    authority.failWith(new IllegalStateException("Authority unavailable"));

    drain(work);

    assertThat(streams.getAllSessions()).isEmpty();
    assertThat(playback.access(request(initial, command))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should preserve the creation rejection when initial playback has no eligible format")
  void shouldPreserveTheCreationRejectionWhenInitialPlaybackHasNoEligibleFormat() {
    streams.rejection = new CreateStreamSessionRejection.NoEligibleVideoFormat();
    var command = startCommand().build();
    assertThat(service.start(command)).isEqualTo(Outcome.rejected(streams.rejection));
    assertThat(streams.getAllSessions()).isEmpty();
    service.closeAll();
  }

  @Test
  @DisplayName("Should retain the typed rejection when replacement has no eligible format")
  void shouldRetainTheTypedRejectionWhenReplacementHasNoEligibleFormat() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.rejection = new CreateStreamSessionRejection.NoEligibleVideoFormat();
    var failed = service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(failed.formatAttemptCount()).isEqualTo(2);
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(new PlaybackSessionFailure.CreationRejected(streams.rejection)));
    assertThat(service.reportFormatFailure(report(failed, command).build())).contains(failed);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should discard a late replacement when its playback closes during creation")
  void shouldDiscardALateReplacementWhenItsPlaybackClosesDuringCreation(
      boolean beforeRegistration) {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    Consumer<CreateStreamSessionCommand> close = _ -> service.close(request(initial, command));
    if (beforeRegistration) {
      streams.beforeNextCreation = close;
    }

    if (!beforeRegistration) {
      streams.duringNextCreation = close;
    }

    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should expire a pending reservation when no replacement has started")
  void shouldExpireAPendingReservationWhenNoReplacementHasStarted() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.reportFormatFailure(report(initial, command).build());
    clock.advance(Duration.ofHours(25));
    playback.reapExpired(clock.instant());
    drain(work);
    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should retain active playback when its child was accessed after logical status")
  void shouldRetainActivePlaybackWhenItsChildWasAccessedAfterLogicalStatus() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var child = streams.getAllSessions().iterator().next();
    clock.advance(Duration.ofHours(23));
    child.setLastAccessedAt(clock.instant());
    clock.advance(Duration.ofHours(2));
    service.reapExpired(clock.instant());
    assertThat(service.access(request(initial, command))).contains(initial);
    clock.advance(Duration.ofHours(25));
    service.reapExpired(clock.instant());
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should remove all ownership when one child cleanup fails during shutdown")
  void shouldRemoveAllOwnershipWhenOneChildCleanupFailsDuringShutdown() {
    var command = startCommand().build();
    var first = accepted(service.start(command));
    var second = accepted(service.start(command));
    streams.failDestroy = true;
    assertThatThrownBy(service::closeAll)
        .isInstanceOf(IllegalStateException.class)
        .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
    assertThat(service.access(request(first, command))).isEmpty();
    assertThat(service.access(request(second, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
    service.closeAll();
  }

  @Test
  @DisplayName("Should preserve startup failure when cleanup also fails")
  void shouldPreserveStartupFailureWhenCleanupAlsoFails() {
    var failure = new IllegalArgumentException("Startup failed");
    streams.duringNextCreation =
        _ -> {
          throw failure;
        };
    streams.failDestroy = true;
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isSameAs(failure)
        .satisfies(result -> assertThat(result.getSuppressed()).hasSize(1));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should settle a replacement when retirement cleanup fails")
  void shouldSettleAReplacementWhenRetirementCleanupFails() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.failDestroy = true;
    var failed = service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason()).isEqualTo(new PlaybackSessionFailure.StartupFailed()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore stale reports when logical playback is already closed")
  void shouldIgnoreStaleReportsWhenLogicalPlaybackIsAlreadyClosed() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    service.close(request(initial, command));
    service.close(request(initial, command));
    service.closeOwningStream(initial.currentFormatAttempt().streamSessionId());
    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));
    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should recover with defaults when the client omits quality and codec lists")
  void shouldRecoverWithDefaultsWhenTheClientOmitsQualityAndCodecLists() {
    var command = startCommand().options(StreamingOptions.builder().build()).build();
    var initial = accepted(service.start(command));
    assertThat(
            service
                .reportFormatFailure(report(initial, command).build())
                .orElseThrow()
                .formatAttemptCount())
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Should require a selected profile when starting or accessing logical playback")
  void shouldRequireASelectedProfileWhenStartingOrAccessingLogicalPlayback() {
    var account = accountScopedBuilder().build();
    var withoutProfile = startCommand().identity(account).build();
    assertThatThrownBy(() -> service.start(withoutProfile))
        .isInstanceOf(AuthenticationRequiredException.class);
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var accountRequest =
        PlaybackSessionRequest.builder()
            .playbackSessionId(initial.playbackSessionId())
            .identity(account)
            .build();
    assertThatThrownBy(() -> service.access(accountRequest))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThat(service.access(request(initial, command))).contains(initial);
  }

  @Test
  @DisplayName("Should reject an absent creation command before registering logical playback")
  void shouldRejectAnAbsentCreationCommandBeforeRegisteringLogicalPlayback() {
    assertThatThrownBy(() -> service.start(null)).isInstanceOf(NullPointerException.class);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should close logical playback when a worker mismatch cannot pass live authority")
  void shouldCloseLogicalPlaybackWhenAWorkerMismatchCannotPassLiveAuthority(boolean throwsFailure) {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    if (throwsFailure) {
      authority.failWith(new IllegalStateException("Authority unavailable"));
    }

    if (!throwsFailure) {
      authority.deny();
    }

    service.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));
    assertThat(service.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should discard a failure claim when playback closes during its authority check")
  void shouldDiscardAFailureClaimWhenPlaybackClosesDuringItsAuthorityCheck() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    authority.onNextCheck(
        () -> service.closeOwningStream(initial.currentFormatAttempt().streamSessionId()));
    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should discard reserved creation when playback closes while its old child retires")
  void shouldDiscardReservedCreationWhenPlaybackClosesWhileItsOldChildRetires() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.afterNextDestroy = () -> service.close(request(initial, command));
    assertThat(service.reportFormatFailure(report(initial, command).build())).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should preserve the original failure when creation and cleanup throw the same object")
  void shouldPreserveTheOriginalFailureWhenCreationAndCleanupThrowTheSameObject() {
    var failure = new IllegalStateException("Shared failure");
    streams.duringNextCreation =
        _ -> {
          throw failure;
        };
    streams.destroyFailure = failure;
    var start = startCommand().build();
    assertThatThrownBy(() -> service.start(start))
        .isSameAs(failure)
        .satisfies(result -> assertThat(result.getSuppressed()).isEmpty());
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should return the new pending reservation when initial copy fails before creation returns")
  void shouldReturnTheNewPendingReservationWhenInitialCopyFailsBeforeCreationReturns() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    streams.duringNextCreation =
        child ->
            playback.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(
                    child.streamSessionId(), UUID.randomUUID(), "default"));
    var command = startCommand().build();
    var pending = accepted(playback.start(command));
    assertThat(pending.formatAttemptCount()).isEqualTo(2);
    assertThat(pending.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(streams.getAllSessions()).isEmpty();
    drain(work);
    assertThat(playback.access(request(pending, command)).orElseThrow().currentFormatAttempt())
        .isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(streams.getActiveSessionCount()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should abandon the logical owner when its unpublished initial child has been replaced")
  void shouldAbandonTheLogicalOwnerWhenItsUnpublishedInitialChildHasBeenReplaced() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.reportFormatFailure(report(initial, command).build());
    authority.deny();

    playback.abandonStart(initial.playbackSessionId());

    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
    drain(work);
    playback.abandonStart(initial.playbackSessionId());
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should finish asynchronous replacement and cleanup when its owned executor closes")
  void shouldFinishAsynchronousReplacementAndCleanupWhenItsOwnedExecutorCloses() {
    var playback =
        PlaybackSessionService.builder()
            .streamingService(streams)
            .authorityGate(authority)
            .maximumFormatAttempts(2)
            .sessionRetention(Duration.ofHours(24))
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    try (playback) {
      playback.reportFormatFailure(report(initial, command).build());
      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(
              () -> {
                var current = playback.access(request(initial, command)).orElseThrow();
                assertThat(current.formatAttemptCount()).isEqualTo(2);
                assertThat(current.currentFormatAttempt())
                    .isInstanceOf(PlaybackFormatAttempt.Ready.class);
                assertThat(streams.getActiveSessionCount()).isEqualTo(1);
              });
    }

    assertThat(playback.access(request(initial, command))).isEmpty();
    assertThat(streams.getAllSessions()).isEmpty();
  }

  private static void drain(ConcurrentLinkedQueue<Runnable> work) {
    for (var next = work.poll(); next != null; next = work.poll()) {
      next.run();
    }
  }

  private PlaybackSessionRequest request(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
    return PlaybackSessionRequest.builder()
        .playbackSessionId(snapshot.playbackSessionId())
        .identity(command.identity())
        .build();
  }

  private ReportPlaybackFormatFailureCommand.ReportPlaybackFormatFailureCommandBuilder report(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
    return ReportPlaybackFormatFailureCommand.builder()
        .playbackSessionId(snapshot.playbackSessionId())
        .streamSessionId(snapshot.currentFormatAttempt().streamSessionId())
        .identity(command.identity())
        .reason(PlaybackFormatFailure.DECODE_ERROR);
  }

  private static void assertThatOriginalPlaysAnEncodeInAutoAfterItsCopyFailed(
      PlaybackSessionSnapshot snapshot) {
    assertThat(snapshot.requestedChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(snapshot.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    assertThat(snapshot.formatAttemptCount()).isEqualTo(2);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(ready.session().getOptions().quality()).isEqualTo(VideoQuality.AUTO);
            });
  }

  private static void assertThatTheFixedQualityPlaysAnEncodeAfterItsCopyFailed(
      PlaybackSessionSnapshot snapshot, VideoQuality quality) {
    assertThat(snapshot.requestedChoice()).isEqualTo(quality);
    assertThat(snapshot.playbackChoice()).isEqualTo(quality);
    assertThat(snapshot.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    assertThat(snapshot.formatAttemptCount()).isEqualTo(2);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(ready.session().getOptions().quality()).isEqualTo(quality);
            });
  }

  private PlaybackSessionService.PlaybackSessionServiceBuilder serviceRunning(Executor executor) {
    return PlaybackSessionService.builder()
        .streamingService(streams)
        .authorityGate(authority)
        .executor(executor)
        .sessionRetention(Duration.ofHours(24))
        .clock(clock);
  }

  private static StreamingOptions qualityOptions(VideoQuality quality) {
    return StreamingOptions.builder().quality(quality).build();
  }

  private StartPlaybackSessionCommand.StartPlaybackSessionCommandBuilder startCommand() {
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(UUID.randomUUID())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build())
        .capabilityDeclaration(declarationBuilderFor(H264, HEVC).build());
  }

  private static PlaybackSessionSnapshot accepted(
      Outcome<PlaybackSessionSnapshot, StartPlaybackSessionRejection> outcome) {
    return outcome.fold(
        result -> result,
        rejections -> {
          throw new AssertionError(rejections);
        });
  }

  private static class ChildStreams implements StreamingService {
    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();
    private final List<CreateStreamSessionCommand> createdCommands = new CopyOnWriteArrayList<>();
    private Consumer<CreateStreamSessionCommand> duringNextCreation;
    private Consumer<CreateStreamSessionCommand> beforeNextCreation;
    private CreateStreamSessionRejection rejection;
    private boolean failDestroy;
    private Runnable afterNextDestroy;
    private RuntimeException destroyFailure;
    // Whether a capability entry accepts a copy of the source, as the transcode decision finds.
    private boolean copiesTheSource = true;
    private MediaProbe sourceProbe = defaultProbeBuilder().build();

    @Override
    public Outcome<StreamSession, CreateStreamSessionRejection> createSession(
        CreateStreamSessionCommand command) {
      createdCommands.add(command);
      if (beforeNextCreation != null) {
        var callback = beforeNextCreation;
        beforeNextCreation = null;
        callback.accept(command);
      }

      if (rejection != null) {
        return Outcome.rejected(rejection);
      }

      var encodes =
          command.videoFormatConstraint() == VideoFormatConstraint.ENCODE_ONLY || !copiesTheSource;

      var session =
          defaultSessionBuilder()
              .sessionId(command.streamSessionId())
              .mediaFileId(command.mediaFileId())
              .authority(command.identity().playbackAuthority())
              .options(command.options())
              .mediaProbe(sourceProbe)
              .transcodeDecision(encodes ? fullTranscodeDecision("h264") : remuxDecision())
              .build();
      sessions.put(session.getSessionId(), session);
      if (duringNextCreation != null) {
        var callback = duringNextCreation;
        duringNextCreation = null;
        callback.accept(command);
      }

      return Outcome.accepted(session);
    }

    @Override
    public Optional<StreamSession> accessSession(PlaybackRequest request) {
      return Optional.ofNullable(sessions.get(request.streamSessionId()));
    }

    @Override
    public void destroySession(UUID id) {
      sessions.remove(id);
      if (afterNextDestroy != null) {
        var callback = afterNextDestroy;
        afterNextDestroy = null;
        callback.run();
      }

      if (destroyFailure != null) {
        throw destroyFailure;
      }

      if (failDestroy) {
        throw new IllegalStateException("Cleanup failed");
      }
    }

    @Override
    public void destroySession(UUID id, UUID profileId) {
      destroySession(id);
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return List.copyOf(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }
  }
}
