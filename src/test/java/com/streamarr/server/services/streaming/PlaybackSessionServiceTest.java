package com.streamarr.server.services.streaming;

import static com.streamarr.server.domain.streaming.ProbedCodecFamily.H264;
import static com.streamarr.server.domain.streaming.ProbedCodecFamily.HEVC;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.directPlaySessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecisionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.ShortCopy;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.PlaybackSessionClosedException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.events.watchprogress.StreamSessionTimelineReportedEvent;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.streaming.events.CopiedVariantRanShortEvent;
import com.streamarr.server.services.streaming.events.DolbyVisionConversionFailedEvent;
import com.streamarr.server.services.streaming.events.MediaSegmentStoredEvent;
import com.streamarr.server.services.streaming.events.PlaybackSessionChangedEvent;
import com.streamarr.server.services.streaming.events.ProducedRangeMayHaveMovedEvent;
import com.streamarr.server.services.streaming.events.RequestedVariantChangedEvent;
import com.streamarr.server.services.streaming.events.RequestedVariantHardwareEncoderFailedEvent;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
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
  private static final Duration RETENTION = Duration.ofHours(24);
  private final ChildStreams streams = new ChildStreams();
  private final FakePlaybackAuthorityGate authority = new FakePlaybackAuthorityGate();
  private final MutableClock clock = new MutableClock();
  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final PlaybackSessionService service =
      PlaybackSessionService.builder()
          .eventPublisher(events)
          .streamingService(streams)
          .authorityGate(authority)
          .executor(Runnable::run)
          .maximumFormatAttempts(2)
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

  @Test
  @DisplayName(
      "Should keep the stream decision's reasons, source and output when playback starts with an"
          + " encode")
  void shouldKeepTheStreamDecisionsReasonsSourceAndOutputWhenPlaybackStartsWithAnEncode() {
    streams.copiesTheSource = false;
    streams.encodeReasons = List.of(DecisionReason.VIDEO_CODEC_PROFILE_NOT_DECLARED);

    var snapshot = accepted(service.start(startCommand().build()));

    assertThat(snapshot.decisions())
        .singleElement()
        .satisfies(
            decision -> {
              assertThat(decision.formatAttempt()).isEqualTo(1);
              assertThat(decision.streamSessionId())
                  .isEqualTo(snapshot.currentFormatAttempt().streamSessionId());
              assertThat(decision.reasons())
                  .containsExactly(DecisionReason.VIDEO_CODEC_PROFILE_NOT_DECLARED);
              assertThat(decision.video().delivery()).isEqualTo(PlaybackDelivery.ENCODE);
              assertThat(decision.video().source())
                  .extracting(VideoFormat::codec, VideoFormat::width, VideoFormat::height)
                  .containsExactly("h264", 1920, 1080);
              assertThat(decision.video().output())
                  .extracting(
                      VideoFormat::codec,
                      VideoFormat::width,
                      VideoFormat::height,
                      VideoFormat::bitrate)
                  .containsExactly("h264", 1280, 720, OptionalLong.of(3_000_000L));
              assertThat(decision.audio())
                  .hasValueSatisfying(
                      audio -> {
                        assertThat(audio.delivery()).isEqualTo(PlaybackDelivery.ENCODE);
                        assertThat(audio.source().codec()).isEqualTo("aac");
                        assertThat(audio.output())
                            .extracting(AudioFormat::codec, AudioFormat::channels)
                            .containsExactly("aac", OptionalInt.of(2));
                      });
            });
  }

  @Test
  @DisplayName(
      "Should report the measured source rate when the frame-rate rule's output rate is lower")
  void shouldReportTheMeasuredSourceRateWhenTheFrameRateRulesOutputRateIsLower() {
    var facts =
        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
            .frameRateRule(
                VideoPlaybackFactsFixture.packetScanRule(119.88, new Fraction(60_000, 1_001)))
            .build();
    streams.sourceProbe = defaultProbeBuilder().videoPlaybackFacts(Optional.of(facts)).build();

    var snapshot = accepted(service.start(startCommand().build()));

    assertThat(snapshot.decisions())
        .singleElement()
        .satisfies(
            decision -> {
              assertThat(decision.video().delivery()).isEqualTo(PlaybackDelivery.COPY);
              assertThat(decision.video().source().frameRate()).hasValue(119.88);
              assertThat(decision.video().output().frameRate()).hasValue(119.88);
            });
  }

  @Test
  @DisplayName("Should start playback and name the codec unknown when the probe named no codec")
  void shouldStartPlaybackAndNameTheCodecUnknownWhenTheProbeNamedNoCodec() {
    streams.copiesTheSource = false;
    streams.sourceProbe = defaultProbeBuilder().videoCodec(null).build();

    var snapshot = accepted(service.start(startCommand().build()));

    assertThat(snapshot.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(snapshot.decisions())
        .singleElement()
        .satisfies(
            decision -> {
              assertThat(decision.video().source().codec()).isEqualTo("unknown");
              assertThat(decision.video().output().codec()).isEqualTo("h264");
            });
  }

  @Test
  @DisplayName(
      "Should keep the copy's reasons and add the copy failure to the encode's when a copy falls"
          + " back to an encode")
  void shouldKeepTheCopysReasonsAndAddTheCopyFailureToTheEncodesWhenACopyFallsBackToAnEncode() {
    streams.copyReasons = List.of(DecisionReason.DOLBY_VISION_NOT_DECLARED);
    streams.encodeReasons = List.of(DecisionReason.AUDIO_CODEC_NOT_DECLARED);
    var command = startCommand().build();
    var initial = accepted(service.start(command));

    var recovered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(recovered.decisions())
        .extracting(
            PlaybackDecision::formatAttempt,
            decision -> decision.video().delivery(),
            PlaybackDecision::reasons)
        .containsExactly(
            tuple(1, PlaybackDelivery.COPY, List.of(DecisionReason.DOLBY_VISION_NOT_DECLARED)),
            tuple(
                2,
                PlaybackDelivery.ENCODE,
                List.of(DecisionReason.COPY_FAILED, DecisionReason.AUDIO_CODEC_NOT_DECLARED)));
    assertThat(recovered.decisions().getFirst().video().output())
        .isEqualTo(recovered.decisions().getFirst().video().source());
  }

  @Test
  @DisplayName(
      "Should keep the source as the direct play's output and name the direct-play failure when"
          + " direct play falls back to HLS")
  void shouldKeepTheSourceAsTheDirectPlaysOutputAndNameTheDirectPlayFailureWhenItFallsBack() {
    streams.sourceProbe = defaultProbeBuilder().audioChannels(OptionalInt.of(6)).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(service, command);

    var recovered = service.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    assertThat(recovered.decisions())
        .extracting(decision -> decision.video().delivery(), PlaybackDecision::reasons)
        .containsExactly(
            tuple(PlaybackDelivery.DIRECT_PLAY, List.of()),
            tuple(PlaybackDelivery.COPY, List.of(DecisionReason.DIRECT_PLAY_FAILED)));
    var direct = recovered.decisions().getFirst();
    assertThat(direct.video().output()).isEqualTo(direct.video().source());
    assertThat(direct.audio())
        .hasValueSatisfying(
            audio -> {
              assertThat(audio.delivery()).isEqualTo(PlaybackDelivery.DIRECT_PLAY);
              assertThat(audio.source().channels()).hasValue(6);
              assertThat(audio.output()).isEqualTo(audio.source());
            });
  }

  // Decision 131: an unknown source channel count stays unknown; it never borrows the output's.
  // A count of zero says no more than a missing one.
  @ParameterizedTest
  @MethodSource("unknownChannelCounts")
  @DisplayName(
      "Should leave the source's channel count unknown when the probe reported none and the audio"
          + " encodes")
  void shouldLeaveTheSourcesChannelCountUnknownWhenTheProbeReportedNoneAndTheAudioEncodes(
      OptionalInt reported) {
    streams.copiesTheSource = false;
    streams.sourceProbe = defaultProbeBuilder().audioChannels(reported).build();

    var snapshot = accepted(service.start(startCommand().build()));

    assertThat(snapshot.decisions().getFirst().audio())
        .hasValueSatisfying(
            audio -> {
              assertThat(audio.source().channels()).isEmpty();
              assertThat(audio.output().channels()).hasValue(2);
            });
  }

  @ParameterizedTest
  @MethodSource("unknownChannelCounts")
  @DisplayName(
      "Should show the direct play's audio with an unknown channel count when the probe reported"
          + " none")
  void shouldShowTheDirectPlaysAudioWithAnUnknownChannelCountWhenTheProbeReportedNone(
      OptionalInt reported) {
    streams.sourceProbe = defaultProbeBuilder().audioChannels(reported).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();

    var directPlay = startDirectPlay(service, command);

    assertThat(directPlay.decisions().getFirst().audio())
        .hasValueSatisfying(
            audio -> {
              assertThat(audio.delivery()).isEqualTo(PlaybackDelivery.DIRECT_PLAY);
              assertThat(audio.source().codec()).isEqualTo("aac");
              assertThat(audio.source().channels()).isEmpty();
              assertThat(audio.output()).isEqualTo(audio.source());
            });
  }

  // A copy delivers the source's audio as it is, so its output repeats the source's channel count
  // and bitrate, known or not, instead of the planning defaults.
  @ParameterizedTest
  @MethodSource("copiedAudioSources")
  @DisplayName("Should describe the copied audio as its source when the audio copies")
  void shouldDescribeTheCopiedAudioAsItsSourceWhenTheAudioCopies(MediaProbe source) {
    streams.sourceProbe = source;

    var snapshot = accepted(service.start(startCommand().build()));

    assertThat(snapshot.decisions().getFirst().audio())
        .hasValueSatisfying(
            audio -> {
              assertThat(audio.delivery()).isEqualTo(PlaybackDelivery.COPY);
              assertThat(audio.source().channels()).isEqualTo(source.audioChannels());
              assertThat(audio.source().bitrate()).isEqualTo(source.audioBitrate());
              assertThat(audio.output()).isEqualTo(audio.source());
            });
  }

  static Stream<Named<MediaProbe>> copiedAudioSources() {
    return Stream.of(
        Named.of(
            "no channel count or bitrate",
            defaultProbeBuilder()
                .audioChannels(OptionalInt.empty())
                .audioBitrate(OptionalLong.empty())
                .build()),
        Named.of(
            "six channels at 640 kb/s",
            defaultProbeBuilder()
                .audioChannels(OptionalInt.of(6))
                .audioBitrate(OptionalLong.of(640_000L))
                .build()));
  }

  static Stream<Named<OptionalInt>> unknownChannelCounts() {
    return Stream.of(
        Named.of("no channel count", OptionalInt.empty()),
        Named.of("a channel count of zero", OptionalInt.of(0)));
  }

  @Test
  @DisplayName("Should name the encode failure when an encode falls back to another encode")
  void shouldNameTheEncodeFailureWhenAnEncodeFallsBackToAnotherEncode() {
    streams.copiesTheSource = false;
    var command = startCommand().build();
    var initial = accepted(service.start(command));

    var recovered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(recovered.decisions())
        .extracting(PlaybackDecision::reasons)
        .containsExactly(List.of(), List.of(DecisionReason.ENCODE_FAILED));
  }

  @Test
  @DisplayName(
      "Should announce a playback session's start, format attempts, timeline and end when they"
          + " change")
  void shouldAnnounceAPlaybackSessionsStartFormatAttemptsTimelineAndEndWhenTheyChange() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var started = changesOf(initial);

    service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    var afterFallback = changesOf(initial);
    var replacement = streams.createdCommands.getLast().streamSessionId();
    service.onStreamSessionTimelineReported(new StreamSessionTimelineReportedEvent(replacement));
    var afterTimeline = changesOf(initial);
    service.close(request(initial, command));

    assertThat(started).isEqualTo(1);
    assertThat(afterFallback).isEqualTo(3);
    assertThat(afterTimeline).isEqualTo(4);
    assertThat(changesOf(initial)).isEqualTo(5);
  }

  @Test
  @DisplayName("Should announce the change when an audio track switch moves direct play to HLS")
  void shouldAnnounceTheChangeWhenAnAudioTrackSwitchMovesDirectPlayToHls() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    var beforeSwitch = changesOf(directPlay);

    accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(changesOf(directPlay)).isEqualTo(beforeSwitch + 1);
  }

  @Test
  @DisplayName("Should announce nothing when a timeline report names a stream that no session owns")
  void shouldAnnounceNothingWhenATimelineReportNamesAStreamThatNoSessionOwns() {
    service.onStreamSessionTimelineReported(
        new StreamSessionTimelineReportedEvent(UUID.randomUUID()));

    assertThat(events.getEventsOfType(PlaybackSessionChangedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should announce that the produced range may have moved when the replacement format attempt's"
          + " stream session stores a media segment")
  void shouldAnnounceThatTheProducedRangeMayHaveMovedWhenTheReplacementStoresAMediaSegment() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    var replacement = streams.createdCommands.getLast().streamSessionId();
    events.clear();

    service.onMediaSegmentStored(new MediaSegmentStoredEvent(replacement));

    assertThat(events.getEventsOfType(ProducedRangeMayHaveMovedEvent.class))
        .containsExactly(new ProducedRangeMayHaveMovedEvent(initial.playbackSessionId()));
  }

  @Test
  @DisplayName(
      "Should announce nothing when a retired format attempt's or an unknown stream session stores a"
          + " media segment")
  void shouldAnnounceNothingWhenARetiredOrUnknownStreamSessionStoresAMediaSegment() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var retired = initial.currentFormatAttempt().streamSessionId();
    service.reportFormatFailure(report(initial, command).build()).orElseThrow();
    events.clear();

    service.onMediaSegmentStored(new MediaSegmentStoredEvent(retired));
    service.onMediaSegmentStored(new MediaSegmentStoredEvent(UUID.randomUUID()));

    assertThat(events.getEventsOfType(ProducedRangeMayHaveMovedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should announce that the produced range may have moved when the current format attempt's"
          + " stream session changes its requested variant")
  void shouldAnnounceThatTheProducedRangeMayHaveMovedWhenTheStreamChangesItsRequestedVariant() {
    var playback = accepted(service.start(startCommand().build()));
    var stream = playback.currentFormatAttempt().streamSessionId();

    service.onRequestedVariantChanged(new RequestedVariantChangedEvent(stream));
    service.onRequestedVariantChanged(new RequestedVariantChangedEvent(UUID.randomUUID()));

    assertThat(events.getEventsOfType(ProducedRangeMayHaveMovedEvent.class))
        .containsExactly(new ProducedRangeMayHaveMovedEvent(playback.playbackSessionId()));
  }

  @Test
  @DisplayName(
      "Should report each open playback session's activity without keeping it open when activity is"
          + " read")
  void shouldReportEachOpenPlaybackSessionsActivityWithoutKeepingItOpenWhenActivityIsRead() {
    var command = startCommand().build();
    var snapshot = accepted(service.start(command));
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      session.setLastAccessedAt(clock.instant());
    }

    clock.advance(Duration.ofHours(25));

    var activity = service.activity();
    service.reapExpired(clock.instant(), RETENTION);

    assertThat(activity)
        .singleElement()
        .satisfies(
            open -> {
              assertThat(open.playbackSessionId()).isEqualTo(snapshot.playbackSessionId());
              assertThat(open.authority()).isEqualTo(command.identity().playbackAuthority());
              assertThat(open.mediaFileId()).isEqualTo(command.mediaFileId());
              assertThat(open.currentFormatAttempt()).isEqualTo(snapshot.currentFormatAttempt());
              assertThat(open.decision()).isEqualTo(snapshot.decisions().stream().findFirst());
            });
    assertThat(service.activity()).isEmpty();
    assertThat(service.activity(snapshot.playbackSessionId())).isEmpty();
  }

  private long changesOf(PlaybackSessionSnapshot snapshot) {
    return events.getEventsOfType(PlaybackSessionChangedEvent.class).stream()
        .filter(event -> event.playbackSessionId().equals(snapshot.playbackSessionId()))
        .count();
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
  @DisplayName("Should start the first format attempt at the start position when playback starts")
  void shouldStartTheFirstFormatAttemptAtTheStartPositionWhenPlaybackStarts() {
    var command = startCommand().startPosition(Optional.of(Duration.ofMillis(5_381_500))).build();

    var snapshot = accepted(service.start(command));

    assertThat(streams.createdCommands)
        .singleElement()
        .extracting(CreateStreamSessionCommand::startPosition)
        .isEqualTo(Optional.of(Duration.ofMillis(5_381_500)));
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getStartPosition())
                    .contains(Duration.ofMillis(5_381_500)));
  }

  @Test
  @DisplayName("Should start the first format attempt without a start position when none is given")
  void shouldStartTheFirstFormatAttemptWithoutAStartPositionWhenNoneIsGiven() {
    accepted(service.start(startCommand().build()));

    assertThat(streams.createdCommands)
        .singleElement()
        .extracting(CreateStreamSessionCommand::startPosition)
        .isEqualTo(Optional.empty());
  }

  // The player reports whole seconds, so a report at the start position's second has not moved.
  @ParameterizedTest
  @ValueSource(ints = {-1, 5_381})
  @DisplayName(
      "Should start the replacement at the start position when playback has not moved from it")
  void shouldStartTheReplacementAtTheStartPositionWhenPlaybackHasNotMovedFromIt(
      int reportedSeconds) {
    var command = startCommand().startPosition(Optional.of(Duration.ofMillis(5_381_500))).build();
    var initial = accepted(service.start(command));
    if (reportedSeconds >= 0) {
      readySession(initial).updatePlaybackState(reportedSeconds, PlaybackState.PLAYING);
    }

    var recovered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(streams.createdCommands.getLast().startPosition())
        .contains(Duration.ofMillis(5_381_500));
  }

  @Test
  @DisplayName(
      "Should start the replacement at the start position when a copy fails before it is ready")
  void shouldStartTheReplacementAtTheStartPositionWhenACopyFailsBeforeItIsReady() {
    streams.duringNextCreation =
        creation ->
            service.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(
                    creation.streamSessionId(), UUID.randomUUID(), "default"));
    var command = startCommand().startPosition(Optional.of(Duration.ofSeconds(600))).build();

    accepted(service.start(command));

    assertThat(streams.createdCommands).hasSize(2);
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            replacement -> {
              assertThat(replacement.videoFormatConstraint())
                  .isEqualTo(VideoFormatConstraint.ENCODE_ONLY);
              assertThat(replacement.startPosition()).contains(Duration.ofSeconds(600));
            });
  }

  // A report can still reach a format attempt after its replacement is reserved and before the
  // retired stream session is destroyed.
  @Test
  @DisplayName(
      "Should start the replacement without the start position when the retired format attempt"
          + " reports a move")
  void shouldStartTheReplacementWithoutTheStartPositionWhenTheRetiredFormatAttemptReportsAMove() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback = serviceRunning(work::add).maximumFormatAttempts(2).build();
    var command = startCommand().startPosition(Optional.of(Duration.ofMillis(600_500))).build();
    var first = accepted(playback.start(command));
    playback.reportFormatFailure(report(first, command).build()).orElseThrow();
    readySession(first).updatePlaybackState(610, PlaybackState.PLAYING);

    drain(work);

    assertThat(streams.createdCommands)
        .extracting(CreateStreamSessionCommand::startPosition)
        .containsExactly(Optional.of(Duration.ofMillis(600_500)), Optional.empty());
  }

  // A seek back to the start second does not undo the earlier move.
  @Test
  @DisplayName(
      "Should start the replacement without the start position when playback moved from it and"
          + " came back")
  void shouldStartTheReplacementWithoutTheStartPositionWhenPlaybackMovedFromItAndCameBack() {
    var command = startCommand().startPosition(Optional.of(Duration.ofMillis(600_500))).build();
    var initial = accepted(service.start(command));
    readySession(initial).updatePlaybackState(610, PlaybackState.PLAYING);
    readySession(initial).updatePlaybackState(600, PlaybackState.PLAYING);

    var recovered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(streams.createdCommands.getLast().startPosition()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should start the replacement without the start position when playback moved from it")
  void shouldStartTheReplacementWithoutTheStartPositionWhenPlaybackMovedFromIt() {
    var command = startCommand().startPosition(Optional.of(Duration.ofMillis(5_381_500))).build();
    var initial = accepted(service.start(command));
    readySession(initial).updatePlaybackState(5_391, PlaybackState.PLAYING);

    var recovered = service.reportFormatFailure(report(initial, command).build()).orElseThrow();

    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(streams.createdCommands.getLast().startPosition()).isEmpty();
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

  // ADR 0050: a tier that a capability entry shrinks into a smaller tier's box gives way, so its
  // fixed quality would play no larger than the smaller tier.
  @ParameterizedTest(name = "{0}")
  @MethodSource("declarationsThatReachSomeBoxes")
  @DisplayName(
      "Should offer only the fixed qualities whose box a capability entry reaches when playback"
          + " starts")
  void shouldOfferOnlyTheFixedQualitiesWhoseBoxACapabilityEntryReachesWhenPlaybackStarts(
      DeclaredSource declared, List<VideoQuality> expected) {
    streams.sourceProbe = declared.source();
    var command = startCommand().capabilityDeclaration(declared.declaration()).build();

    var snapshot = accepted(service.start(command));

    assertThat(snapshot.offeredChoices()).containsExactlyElementsOf(expected);
  }

  private record DeclaredSource(MediaProbe source, ClientCapabilityDeclaration declaration) {}

  private static Stream<Arguments> declarationsThatReachSomeBoxes() {
    var uhdHigh = probedSource(3840, 2160, h264Facts(VideoCodecProfile.H264_HIGH, 51).build());
    var belowUhd =
        List.of(
            VideoQuality.ORIGINAL,
            VideoQuality.AUTO,
            VideoQuality.FULL_HD_1080P,
            VideoQuality.HIGH_720P,
            VideoQuality.MEDIUM_480P,
            VideoQuality.LOW_360P);
    var originalAutoAndUhd = List.of(VideoQuality.ORIGINAL, VideoQuality.AUTO, VideoQuality.UHD_4K);
    return Stream.of(
        Arguments.of(
            Named.of(
                "a UHD source on H.264 up to 1080p at level 4.2",
                new DeclaredSource(uhdHigh, fullHdH264Declaration().build())),
            belowUhd),
        Arguments.of(
            Named.of(
                "a 2.4:1 UHD source on H.264 up to 1080p at level 4.2",
                new DeclaredSource(
                    probedSource(3840, 1600, h264Facts(VideoCodecProfile.H264_HIGH, 51).build()),
                    fullHdH264Declaration().build())),
            belowUhd),
        Arguments.of(
            Named.of(
                "a UHD source on H.264 up to 1080p and AV1 up to UHD",
                new DeclaredSource(
                    uhdHigh,
                    fullHdH264Declaration()
                        .video(List.of(fullHdH264Entry(), av1EntryBuilder().build()))
                        .build())),
            List.of(
                VideoQuality.ORIGINAL,
                VideoQuality.AUTO,
                VideoQuality.UHD_4K,
                VideoQuality.FULL_HD_1080P,
                VideoQuality.HIGH_720P,
                VideoQuality.MEDIUM_480P,
                VideoQuality.LOW_360P)),
        Arguments.of(
            Named.of(
                "a UHD Main source whose copy an H.264 Main entry accepts but whose High encode"
                    + " it refuses",
                new DeclaredSource(
                    probedSource(3840, 2160, h264Facts(VideoCodecProfile.H264_MAIN, 51).build()),
                    defaultDeclarationBuilder()
                        .video(
                            List.of(
                                h264EntryBuilder()
                                    .codecProfiles(
                                        List.of(
                                            VideoCodecProfileCapability.builder()
                                                .profile(VideoCodecProfile.H264_MAIN)
                                                .maxLevel(52)
                                                .build()))
                                    .build()))
                        .build())),
            originalAutoAndUhd),
        // An encode takes the codec family that plays the source at the fastest frame rate.
        Arguments.of(
            Named.of(
                "a UHD source at 60 fps on H.264 up to 1080p60 and AV1 up to UHD at 30 fps",
                new DeclaredSource(
                    probedSource(
                        3840,
                        2160,
                        h264Facts(VideoCodecProfile.H264_HIGH, 52)
                            .frameRateRule(
                                VideoPlaybackFactsFixture.declaredRateRule(new Fraction(60, 1)))
                            .build()),
                    fullHdH264Declaration()
                        .video(
                            List.of(fullHdH264Entry(), av1EntryBuilder().maxFrameRate(30).build()))
                        .build())),
            belowUhd),
        // Deinterlacing doubles the frame rate, so level 3.0 shrinks the 480p encode to 504x336.
        Arguments.of(
            Named.of(
                "an interlaced 480i source whose copy H.264 at level 3.0 accepts but whose"
                    + " deinterlaced encode it shrinks into the 360p box",
                new DeclaredSource(
                    probedSource(
                        720,
                        480,
                        h264Facts(VideoCodecProfile.H264_HIGH, 30)
                            .frameRateRule(
                                VideoPlaybackFactsFixture.interlacedRateRule(
                                    new Fraction(30_000, 1_001), FieldOrder.TT))
                            .build()),
                    defaultDeclarationBuilder()
                        .video(
                            List.of(h264HighEntryBuilder(30).maxWidth(720).maxHeight(480).build()))
                        .build())),
            List.of(VideoQuality.ORIGINAL, VideoQuality.AUTO, VideoQuality.LOW_360P)),
        Arguments.of(
            Named.of(
                "a UHD source probed before version 5 without an output frame rate, which only a"
                    + " copy plays",
                new DeclaredSource(
                    probedSource(
                        3840,
                        2160,
                        VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                            .streamIndex(0)
                            .frameRateRule(VideoPlaybackFactsFixture.refusedRule())
                            .build()),
                    defaultDeclarationBuilder().build())),
            originalAutoAndUhd));
  }

  private static MediaProbe probedSource(int width, int height, VideoPlaybackFacts facts) {
    return defaultProbeBuilder()
        .width(width)
        .height(height)
        .videoPlaybackFacts(Optional.of(facts))
        .build();
  }

  // An 8-bit SDR H.264 stream at 24 fps whose probe names its codec profile and level.
  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder h264Facts(
      VideoCodecProfile profile, int level) {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .streamIndex(0)
        .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
        .videoProperties(
            Optional.of(
                VideoPlaybackFactsFixture.h264HighPropertiesBuilder()
                    .codecProfile(Optional.of(profile))
                    .codecLevel(OptionalInt.of(level))
                    .build()));
  }

  // What the tvOS simulator declares: H.264 High only, up to 1080p at level 4.2.
  private static ClientCapabilityDeclaration.ClientCapabilityDeclarationBuilder
      fullHdH264Declaration() {
    return defaultDeclarationBuilder().video(List.of(fullHdH264Entry()));
  }

  private static VideoCapabilityEntry fullHdH264Entry() {
    return h264HighEntryBuilder(42).maxWidth(1920).maxHeight(1080).build();
  }

  private static VideoCapabilityEntry.VideoCapabilityEntryBuilder h264HighEntryBuilder(
      int maxLevel) {
    return h264EntryBuilder()
        .codecProfiles(
            List.of(
                VideoCodecProfileCapability.builder()
                    .profile(VideoCodecProfile.H264_HIGH)
                    .maxLevel(maxLevel)
                    .build()));
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(Runnable::run)
            .maximumFormatAttempts(maximum)
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

  // ADR 0037: a copy whose segment exceeds the cap on the segment list falls back to an encode
  // under ADR 0052, as any failed copy does.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"AUTO", "ORIGINAL"})
  @DisplayName("Should encode at the same fixed quality when its copy exceeds the segment cap")
  void shouldEncodeAtTheSameFixedQualityWhenItsCopyExceedsTheSegmentCap(VideoQuality quality) {
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(service.start(command));

    service.onTerminalTranscodeFailure(
        terminalFailure(
            initial.currentFormatAttempt().streamSessionId(),
            TranscodeFailure.SEGMENT_CAP_EXCEEDED));

    assertThatTheFixedQualityPlaysAnEncodeAfterItsCopyFailed(
        service.access(request(initial, command)).orElseThrow(), quality);
  }

  @ParameterizedTest
  @CsvSource({
    "AUTO,OUTPUT_MISMATCH",
    "AUTO,INVALID_SPECIFICATION",
    "AUTO,DOLBY_VISION_CONVERSION_FAILED",
    "HIGH_720P,OUTPUT_MISMATCH",
    "ORIGINAL,INVALID_SPECIFICATION",
    "ORIGINAL,DOLBY_VISION_CONVERSION_FAILED"
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

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "HIGH_720P"})
  @DisplayName("Should encode the audio without excluding video when copied audio runs short")
  void shouldEncodeTheAudioWithoutExcludingVideoWhenCopiedAudioRunsShort(VideoQuality quality) {
    streams.copiesTheSource = false;
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(service.start(command));

    service.onTerminalTranscodeFailure(
        terminalFailure(
            initial.currentFormatAttempt().streamSessionId(),
            TranscodeFailure.COPIED_AUDIO_RAN_SHORT));

    var recovered = service.access(request(initial, command)).orElseThrow();
    assertThat(recovered.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(recovered.playbackChoice()).isEqualTo(quality);
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            next -> {
              assertThat(next.audioFormatConstraint()).isEqualTo(AudioFormatConstraint.ENCODE_ONLY);
              assertThat(next.videoFormatConstraint()).isEqualTo(VideoFormatConstraint.ENCODE_ONLY);
              assertThat(next.excludedEncodeCodecFamilies()).isEmpty();
              assertThat(next.excludedEncodeFormats()).isEmpty();
            });
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should change Original only if video must encode when copied audio runs short")
  void shouldChangeOriginalOnlyIfVideoMustEncodeWhenCopiedAudioRunsShort(boolean videoMustEncode) {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var initial = accepted(service.start(command));
    streams.copiesTheSource = !videoMustEncode;

    service.onTerminalTranscodeFailure(
        terminalFailure(
            initial.currentFormatAttempt().streamSessionId(),
            TranscodeFailure.COPIED_AUDIO_RAN_SHORT));

    var recovered = service.access(request(initial, command)).orElseThrow();
    assertThat(recovered.playbackChoice())
        .isEqualTo(videoMustEncode ? VideoQuality.AUTO : VideoQuality.ORIGINAL);
    assertThat(streams.createdCommands.getLast().videoFormatConstraint())
        .isEqualTo(VideoFormatConstraint.COPY_OR_ENCODE);
    assertThat(streams.createdCommands.getLast().audioFormatConstraint())
        .isEqualTo(AudioFormatConstraint.ENCODE_ONLY);
  }

  @Test
  @DisplayName(
      "Should keep the pending video's encode constraint when copied audio fails before creation returns")
  void shouldKeepThePendingVideosEncodeConstraintWhenCopiedAudioFailsBeforeCreationReturns() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback = serviceRunning(work::add).maximumFormatAttempts(3).build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    streams.duringNextCreation =
        child ->
            playback.onTerminalTranscodeFailure(
                terminalFailure(child.streamSessionId(), TranscodeFailure.COPIED_AUDIO_RAN_SHORT));

    playback.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(
            initial.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));
    drain(work);

    assertThat(streams.createdCommands.getLast().videoFormatConstraint())
        .isEqualTo(VideoFormatConstraint.ENCODE_ONLY);
    assertThat(streams.createdCommands.getLast().audioFormatConstraint())
        .isEqualTo(AudioFormatConstraint.ENCODE_ONLY);
    assertThat(playback.access(request(initial, command)).orElseThrow().formatAttemptCount())
        .isEqualTo(3);
  }

  @Test
  @DisplayName("Should ignore duplicate reports when a short audio copy already recovered")
  void shouldIgnoreDuplicateReportsWhenAShortAudioCopyAlreadyRecovered() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    var failure =
        terminalFailure(
            initial.currentFormatAttempt().streamSessionId(),
            TranscodeFailure.COPIED_AUDIO_RAN_SHORT);

    service.onTerminalTranscodeFailure(failure);
    service.onTerminalTranscodeFailure(failure);

    assertThat(service.access(request(initial, command)).orElseThrow().currentFormatAttempt())
        .isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(streams.createdCommands).hasSize(2);
  }

  @Test
  @DisplayName("Should stop at the format budget when copied audio runs short")
  void shouldStopAtTheFormatBudgetWhenCopiedAudioRunsShort() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(1).build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));

    playback.onTerminalTranscodeFailure(
        terminalFailure(
            initial.currentFormatAttempt().streamSessionId(),
            TranscodeFailure.COPIED_AUDIO_RAN_SHORT));

    var failed = playback.access(request(initial, command)).orElseThrow();
    assertThat(failed.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Failed.class);
    assertThat(failed.formatAttemptCount()).isOne();
    assertThat(streams.createdCommands).hasSize(1);
  }

  // ADR 0044: the requested variant whose hardware encoder fails continues on the software encoder
  // of the same codec, and the client reopens it through format recovery (worker#56).
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "HIGH_720P"})
  @DisplayName(
      "Should continue the encode in software without spending a format attempt when the requested"
          + " variant's hardware encoder fails")
  void shouldContinueTheEncodeInSoftwareWhenTheRequestedVariantsHardwareEncoderFails(
      VideoQuality quality) {
    streams.copiesTheSource = false;
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(1).build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var initial = accepted(playback.start(command));

    playback.onRequestedVariantHardwareEncoderFailed(
        hardwareEncoderFailure(initial.currentFormatAttempt().streamSessionId()));

    var continued = playback.access(request(initial, command)).orElseThrow();
    assertThat(continued.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(continued.formatAttemptCount()).isEqualTo(1);
    assertThat(continued.playbackChoice()).isEqualTo(quality);
    assertThat(continued.formatChange()).isEmpty();
    assertThat(continued.decisions())
        .extracting(PlaybackDecision::reasons)
        .last()
        .isEqualTo(List.of(DecisionReason.HARDWARE_ENCODER_FAILED));
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            software -> {
              assertThat(software.softwareEncodeCodecFamily()).contains("h264");
              assertThat(software.videoFormatConstraint())
                  .isEqualTo(VideoFormatConstraint.ENCODE_ONLY);
              assertThat(software.excludedEncodeCodecFamilies()).isEmpty();
            });
    assertThat(streams.getAllSessions()).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should keep the format-attempt budget for a later format failure when a hardware encoder"
          + " failure continued the encode in software")
  void shouldKeepTheFormatAttemptBudgetWhenAHardwareEncoderFailureContinuedInSoftware() {
    streams.copiesTheSource = false;
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(2).build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.onRequestedVariantHardwareEncoderFailed(
        hardwareEncoderFailure(initial.currentFormatAttempt().streamSessionId()));
    var continued = playback.access(request(initial, command)).orElseThrow();

    var recovered = playback.reportFormatFailure(report(continued, command).build()).orElseThrow();

    assertThat(recovered.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(streams.createdCommands.getLast().excludedEncodeCodecFamilies())
        .containsExactly("h264");
    assertThat(streams.createdCommands.getLast().softwareEncodeCodecFamily()).isEmpty();
  }

  @Test
  @DisplayName("Should fail playback when no worker has room for the software encode")
  void shouldFailPlaybackWhenNoWorkerHasRoomForTheSoftwareEncode() {
    streams.copiesTheSource = false;
    var command = startCommand().build();
    var initial = accepted(service.start(command));
    streams.rejection = new CreateStreamSessionRejection.NoEligibleVideoFormat();

    service.onRequestedVariantHardwareEncoderFailed(
        hardwareEncoderFailure(initial.currentFormatAttempt().streamSessionId()));

    var failed = service.access(request(initial, command)).orElseThrow();
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(new PlaybackSessionFailure.CreationRejected(streams.rejection)));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should ignore a hardware encoder failure when the failed format attempt copies the video")
  void shouldIgnoreAHardwareEncoderFailureWhenTheFailedFormatAttemptCopiesTheVideo() {
    var command = startCommand().build();
    var initial = accepted(service.start(command));

    service.onRequestedVariantHardwareEncoderFailed(
        hardwareEncoderFailure(initial.currentFormatAttempt().streamSessionId()));

    var current = service.access(request(initial, command)).orElseThrow();
    assertThat(current.formatAttemptCount()).isEqualTo(1);
    assertThat(current.currentFormatAttempt()).isEqualTo(initial.currentFormatAttempt());
  }

  private static RequestedVariantHardwareEncoderFailedEvent hardwareEncoderFailure(
      UUID streamSessionId) {
    return RequestedVariantHardwareEncoderFailedEvent.builder()
        .streamSessionId(streamSessionId)
        .variantLabel("default")
        .build();
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
      case SEGMENT_CAP_EXCEEDED -> new PlaybackSessionFailure.OutputMismatch();
      case COPIED_AUDIO_RAN_SHORT -> throw new AssertionError("A short audio copy is recoverable");
      case DOLBY_VISION_CONVERSION_FAILED ->
          new PlaybackSessionFailure.DolbyVisionConversionFailed();
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

  // ADR 0045: a format error during direct play falls back to HLS at original quality, and an HLS
  // copy keeps Original without a notice.
  @ParameterizedTest
  @EnumSource(PlaybackFormatFailure.class)
  @DisplayName("Should play an HLS copy in Original without a format change when direct play fails")
  void shouldPlayAnHlsCopyInOriginalWithoutAFormatChangeWhenDirectPlayFails(
      PlaybackFormatFailure reason) {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(service, command);

    var recovered =
        service
            .reportFormatFailure(report(directPlay, command).reason(reason).build())
            .orElseThrow();

    assertThat(recovered.requestedChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(recovered.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(recovered.formatChange()).isEmpty();
    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getDelivery()).isEqualTo(StreamDelivery.HLS);
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isTrue();
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            fallback -> {
              assertThat(fallback.videoFormatConstraint())
                  .isEqualTo(VideoFormatConstraint.COPY_OR_ENCODE);
              assertThat(fallback.options().quality()).isEqualTo(VideoQuality.ORIGINAL);
            });
  }

  // ADR 0045: when the server rules out the HLS copy, the HLS format attempt is an encode, and it
  // plays in Auto with a notice (ADR 0052).
  @Test
  @DisplayName(
      "Should play an encode in Auto with the direct play change when direct play fails and no"
          + " copy is eligible")
  void shouldPlayAnEncodeInAutoWithTheDirectPlayChangeWhenDirectPlayFailsAndNoCopyIsEligible() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(service, command);
    streams.copiesTheSource = false;

    var recovered = service.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    assertThat(recovered.requestedChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(recovered.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(recovered.formatChange()).contains(PlaybackFormatChange.DIRECT_PLAY_FAILED);
    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                    .isFalse());
  }

  // The client learns whether Original stays selected only when the HLS format attempt is ready.
  @Test
  @DisplayName(
      "Should keep Original while the HLS format attempt after a failed direct play is pending")
  void shouldKeepOriginalWhileTheHlsFormatAttemptAfterAFailedDirectPlayIsPending() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback = serviceRunning(work::add).maximumFormatAttempts(2).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(playback, command);
    streams.copiesTheSource = false;

    var pending = playback.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    assertThat(pending.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Pending.class);
    assertThat(pending.formatAttemptCount()).isEqualTo(2);
    assertThat(pending.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(pending.formatChange()).isEmpty();
    drain(work);
    var ready = playback.access(request(directPlay, command)).orElseThrow();
    assertThat(ready.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(ready.formatChange()).contains(PlaybackFormatChange.DIRECT_PLAY_FAILED);
  }

  @Test
  @DisplayName("Should start one HLS format attempt when direct play's failure is reported twice")
  void shouldStartOneHlsFormatAttemptWhenDirectPlaysFailureIsReportedTwice() {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback = serviceRunning(work::add).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(playback, command);
    var first = playback.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    var second = playback.reportFormatFailure(report(directPlay, command).build());

    assertThat(second).contains(first);
    drain(work);
    var ready = playback.access(request(directPlay, command)).orElseThrow();
    assertThat(ready.formatAttemptCount()).isEqualTo(2);
    assertThat(ready.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(streams.createdCommands).hasSize(2);
    assertThat(streams.getActiveSessionCount()).isEqualTo(1);
  }

  // ADR 0052: the default budget of four lets Original reach the encode after direct play and the
  // HLS copy both fail.
  @Test
  @DisplayName("Should reach an encode in Auto when direct play and then the HLS copy fail")
  void shouldReachAnEncodeInAutoWhenDirectPlayAndThenTheHlsCopyFail() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(playback, command);
    var copy = playback.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    var encode = playback.reportFormatFailure(report(copy, command).build()).orElseThrow();

    assertThat(copy.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(encode.formatAttemptCount()).isEqualTo(3);
    assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(encode.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    assertThat(streams.createdCommands.getLast().videoFormatConstraint())
        .isEqualTo(VideoFormatConstraint.ENCODE_ONLY);
    assertThat(encode.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
  }

  // A configured budget with no room for the next output ends playback (ADR 0052).
  @Test
  @DisplayName(
      "Should end with attempts exhausted when the HLS copy after direct play fails with no"
          + " attempt left")
  void shouldEndWithAttemptsExhaustedWhenTheHlsCopyAfterDirectPlayFailsWithNoAttemptLeft() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(2).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(playback, command);
    var copy = playback.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    var failed = playback.reportFormatFailure(report(copy, command).build()).orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(2);
    assertThat(failed.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(failed.formatChange()).isEmpty();
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                copy.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.AttemptsExhausted()));
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should end with attempts exhausted when direct play fails with no attempt left")
  void shouldEndWithAttemptsExhaustedWhenDirectPlayFailsWithNoAttemptLeft() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(1).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(playback, command);

    var failed = playback.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(1);
    assertThat(failed.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                directPlay.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.AttemptsExhausted()));
    assertThat(streams.createdCommands).hasSize(1);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should recover as Auto does when the encode after a failed direct play fails")
  void shouldRecoverAsAutoDoesWhenTheEncodeAfterAFailedDirectPlayFails() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(3).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(playback, command);
    streams.copiesTheSource = false;
    var encode = playback.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    var recovered = playback.reportFormatFailure(report(encode, command).build()).orElseThrow();

    assertThat(recovered.formatAttemptCount()).isEqualTo(3);
    assertThat(recovered.currentFormatAttempt()).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(recovered.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(recovered.formatChange()).contains(PlaybackFormatChange.DIRECT_PLAY_FAILED);
    assertThat(streams.createdCommands.getLast().excludedEncodeCodecFamilies())
        .containsExactly("h264");
  }

  // T2: picking Original again starts a new playback session, so only its first format attempt may
  // direct-play.
  @Test
  @DisplayName("Should let only the first format attempt direct-play")
  void shouldLetOnlyTheFirstFormatAttemptDirectPlay() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(service, command);

    var recovered = service.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    assertThat(streams.createdCommands)
        .extracting(CreateStreamSessionCommand::videoFormatConstraint)
        .containsExactly(
            VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE,
            VideoFormatConstraint.COPY_OR_ENCODE);
    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> assertThat(ready.session().getDelivery()).isEqualTo(StreamDelivery.HLS));
  }

  // ADR 0045: a player that cannot select an audio track in a directly played file moves to HLS for
  // the viewer's newly chosen track, by the move that a failed direct play makes. An HLS copy keeps
  // Original without a notice.
  @Test
  @DisplayName(
      "Should move to an HLS copy in Original with the chosen track when the viewer switches during"
          + " direct play")
  void shouldMoveToAnHlsCopyInOriginalWithTheChosenTrackWhenTheViewerSwitchesDuringDirectPlay() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);

    var switched = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(switched.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(switched.formatChange()).isEmpty();
    assertThat(switched.formatAttemptCount()).isEqualTo(1);
    assertThat(switched.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getDelivery()).isEqualTo(StreamDelivery.HLS);
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            hls -> {
              assertThat(hls.videoFormatConstraint())
                  .isEqualTo(VideoFormatConstraint.COPY_OR_ENCODE);
              assertThat(hls.options().quality()).isEqualTo(VideoQuality.ORIGINAL);
              assertThat(hls.audioTrackPosition()).hasValue(1);
            });
  }

  // The HLS format attempt names why it does not direct-play, as one after a failed direct play
  // names the failure, so a playback-data overlay shows why the viewer's switch moved to HLS.
  @Test
  @DisplayName(
      "Should name the unselectable audio track in the decision of the format attempt that an audio"
          + " switch starts")
  void shouldNameTheUnselectableAudioTrackInTheDecisionOfTheFormatAttemptThatAnAudioSwitchStarts() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);

    var switched = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(switched.decisions())
        .extracting(decision -> decision.video().delivery(), PlaybackDecision::reasons)
        .containsExactly(
            tuple(PlaybackDelivery.DIRECT_PLAY, List.of()),
            tuple(
                PlaybackDelivery.COPY,
                List.of(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE)));
  }

  // ADR 0052: Original that reaches an encode plays it in Auto, and the notice names the switch.
  @Test
  @DisplayName(
      "Should play an encode in Auto with the audio track switch change when no copy is eligible")
  void shouldPlayAnEncodeInAutoWithTheAudioTrackSwitchChangeWhenNoCopyIsEligible() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    streams.copiesTheSource = false;

    var switched = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(switched.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(switched.formatChange()).contains(PlaybackFormatChange.AUDIO_TRACK_SWITCHED);
    assertThat(switched.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                    .isFalse());
  }

  @Test
  @DisplayName("Should keep the chosen audio track in the format attempt after a failed copy")
  void shouldKeepTheChosenAudioTrackInTheFormatAttemptAfterAFailedCopy() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(playback, command);
    var copy = accepted(playback.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    var encode = playback.reportFormatFailure(report(copy, command).build()).orElseThrow();

    assertThat(encode.formatAttemptCount()).isEqualTo(2);
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            next -> {
              assertThat(next.videoFormatConstraint()).isEqualTo(VideoFormatConstraint.ENCODE_ONLY);
              assertThat(next.audioTrackPosition()).hasValue(1);
            });
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 2})
  @DisplayName("Should refuse an audio track that the file does not have")
  void shouldRefuseAnAudioTrackThatTheFileDoesNotHave(int position) {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);

    var outcome = service.switchAudioTrack(audioSwitch(directPlay, command, position));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new AudioTrackSwitchRejection.AudioTrackNotInFile()));
    assertThat(service.access(request(directPlay, command))).contains(directPlay);
    assertThat(streams.createdCommands).hasSize(1);
  }

  // ADR 0043: the move to HLS follows from what the player cannot do, not from an error, so it
  // spends no format attempt, even when direct play used the whole budget.
  @Test
  @DisplayName("Should move to HLS when the viewer switches tracks with no format attempt left")
  void shouldMoveToHlsWhenTheViewerSwitchesTracksWithNoFormatAttemptLeft() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(1).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(playback, command);

    var switched = accepted(playback.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(switched.formatAttemptCount()).isEqualTo(1);
    assertThat(switched.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getDelivery()).isEqualTo(StreamDelivery.HLS);
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
  }

  // ADR 0043: the switch spends no format attempt, so a budget of two still holds the encode that
  // follows a failed copy. Each format attempt keeps its own number.
  @Test
  @DisplayName(
      "Should encode within a budget of two when the copy that an audio switch started fails")
  void shouldEncodeWithinABudgetOfTwoWhenTheCopyThatAnAudioSwitchStartedFails() {
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(2).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(playback, command);
    var copy = accepted(playback.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    var encode = playback.reportFormatFailure(report(copy, command).build()).orElseThrow();

    assertThat(encode.formatAttemptCount()).isEqualTo(2);
    assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(encode.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                    .isFalse());
    assertThat(encode.decisions())
        .extracting(PlaybackDecision::formatAttempt)
        .containsExactly(1, 2, 3);
  }

  // A switch is the viewer's choice, not a failure, so direct play plays on until the HLS format
  // attempt is ready.
  @Test
  @DisplayName(
      "Should keep direct play playing while the HLS stream session for an audio switch starts")
  void shouldKeepDirectPlayPlayingWhileTheHlsStreamSessionForAnAudioSwitchStarts() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    var directPlayId = directPlay.currentFormatAttempt().streamSessionId();
    var directPlayDuringStart = new AtomicReference<Optional<StreamSession>>();
    streams.beforeNextCreation =
        _ -> directPlayDuringStart.set(Optional.ofNullable(streams.sessions.get(directPlayId)));

    accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(directPlayDuringStart.get()).isPresent();
    assertThat(streams.sessions).doesNotContainKey(directPlayId);
  }

  @Test
  @DisplayName(
      "Should keep direct play and report the refusal when the HLS stream session for an audio"
          + " switch is refused")
  void shouldKeepDirectPlayAndReportTheRefusalWhenTheHlsStreamSessionForAnAudioSwitchIsRefused() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    streams.rejection = new CreateStreamSessionRejection.TranscodeCapacityUnavailable(8);

    var outcome = service.switchAudioTrack(audioSwitch(directPlay, command, 1));

    assertThat(outcome)
        .isEqualTo(
            Outcome.rejected(
                new AudioTrackSwitchRejection.StreamSessionRefused(
                    new CreateStreamSessionRejection.TranscodeCapacityUnavailable(8))));
    assertThat(service.access(request(directPlay, command))).contains(directPlay);
    assertThat(streams.sessions).containsKey(directPlay.currentFormatAttempt().streamSessionId());
  }

  @Test
  @DisplayName(
      "Should keep direct play when the HLS stream session for an audio switch fails to start")
  void shouldKeepDirectPlayWhenTheHlsStreamSessionForAnAudioSwitchFailsToStart() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    var startupFailure = new IllegalStateException("Startup failed");
    streams.beforeNextCreation =
        _ -> {
          throw startupFailure;
        };
    var audioSwitch = audioSwitch(directPlay, command, 1);

    assertThatThrownBy(() -> service.switchAudioTrack(audioSwitch)).isSameAs(startupFailure);

    assertThat(service.access(request(directPlay, command))).contains(directPlay);
    assertThat(streams.sessions).containsKey(directPlay.currentFormatAttempt().streamSessionId());
  }

  // Direct play still plays while the switch's stream session starts, so a worker failure of that
  // stream session fails only the switch.
  @ParameterizedTest(name = "{0}")
  @MethodSource("workerFailuresOfTheSwitchesStreamSession")
  @DisplayName(
      "Should keep direct play when the worker fails the stream session of an audio switch before"
          + " it completes")
  void shouldKeepDirectPlayWhenTheWorkerFailsTheStreamSessionOfAnAudioSwitchBeforeItCompletes(
      String scenario, BiConsumer<PlaybackSessionService, UUID> workerFailure) {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    streams.duringNextCreation = next -> workerFailure.accept(service, next.streamSessionId());

    var outcome = service.switchAudioTrack(audioSwitch(directPlay, command, 1));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new AudioTrackSwitchRejection.StreamSessionFailed()));
    assertThat(service.access(request(directPlay, command))).contains(directPlay);
    assertThat(streams.sessions.keySet())
        .containsExactly(directPlay.currentFormatAttempt().streamSessionId());
  }

  private static Stream<Arguments> workerFailuresOfTheSwitchesStreamSession() {
    return Stream.of(
        Arguments.of(
            "a terminal failure",
            (BiConsumer<PlaybackSessionService, UUID>)
                (playback, streamId) ->
                    playback.onTerminalTranscodeFailure(
                        terminalFailure(streamId, TranscodeFailure.INVALID_SPECIFICATION))),
        Arguments.of(
            "a copy output mismatch",
            (BiConsumer<PlaybackSessionService, UUID>)
                (playback, streamId) ->
                    playback.onVideoCopyOutputMismatch(
                        new VideoCopyOutputMismatchEvent(streamId, UUID.randomUUID(), "default"))));
  }

  // A direct-play failure during a switch waits for the switch, whose stream session already moves
  // to HLS with the chosen track.
  @Test
  @DisplayName(
      "Should play the switch's stream session when direct play fails during an audio switch")
  void shouldPlayTheSwitchsStreamSessionWhenDirectPlayFailsDuringAnAudioSwitch() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    streams.duringNextCreation =
        _ -> service.reportFormatFailure(report(directPlay, command).build());

    var switched = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(switched.formatAttemptCount()).isEqualTo(1);
    assertThat(switched.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> assertThat(streams.getAllSessions()).containsExactly(ready.session()));
    assertThat(streams.createdCommands).hasSize(2);
    assertThat(streams.createdCommands.getLast().audioTrackPosition()).hasValue(1);
  }

  @Test
  @DisplayName(
      "Should recover with the chosen audio track when direct play and the switch's stream session"
          + " both fail")
  void shouldRecoverWithTheChosenAudioTrackWhenDirectPlayAndTheSwitchsStreamSessionBothFail() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    streams.duringNextCreation =
        next -> {
          service.reportFormatFailure(report(directPlay, command).build());
          service.onTerminalTranscodeFailure(
              terminalFailure(next.streamSessionId(), TranscodeFailure.INVALID_SPECIFICATION));
        };

    var recovered = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(recovered.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> assertThat(streams.getAllSessions()).containsExactly(ready.session()));
    assertThat(streams.createdCommands).hasSize(3);
    assertThat(streams.createdCommands.getLast().audioTrackPosition()).hasValue(1);
  }

  // Once the switch has ended, a second report of the same direct-play failure recovers before the
  // held-back one does, and must play the chosen track too.
  @Test
  @DisplayName(
      "Should recover with the chosen audio track when direct play fails again as a failed audio"
          + " switch ends")
  void shouldRecoverWithTheChosenAudioTrackWhenDirectPlayFailsAgainAsAFailedAudioSwitchEnds() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    streams.duringNextCreation =
        next -> {
          service.reportFormatFailure(report(directPlay, command).build());
          service.onTerminalTranscodeFailure(
              terminalFailure(next.streamSessionId(), TranscodeFailure.INVALID_SPECIFICATION));
          streams.afterNextDestroy =
              () -> service.reportFormatFailure(report(directPlay, command).build());
        };

    var recovered = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(recovered.formatAttemptCount()).isEqualTo(2);
    assertThat(streams.createdCommands).hasSize(3);
    assertThat(streams.createdCommands.getLast().audioTrackPosition()).hasValue(1);
  }

  // The reaper ends a playback session whose direct play went quiet, even during a switch.
  @Test
  @DisplayName(
      "Should report a closed playback session when the reaper ends direct play during an audio"
          + " switch")
  void shouldReportAClosedPlaybackSessionWhenTheReaperEndsDirectPlayDuringAnAudioSwitch() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    streams.duringNextCreation =
        _ -> service.closeOwningStream(directPlay.currentFormatAttempt().streamSessionId());

    var outcome = service.switchAudioTrack(audioSwitch(directPlay, command, 1));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new AudioTrackSwitchRejection.PlaybackSessionClosed()));
    assertThat(streams.sessions).isEmpty();
  }

  @Test
  @DisplayName("Should start nothing when the viewer switches again while an audio switch starts")
  void shouldStartNothingWhenTheViewerSwitchesAgainWhileAnAudioSwitchStarts() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    var second = new AtomicReference<PlaybackSessionSnapshot>();
    streams.duringNextCreation =
        _ -> second.set(accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1))));

    accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    assertThat(second.get()).isEqualTo(directPlay);
    assertThat(streams.createdCommands).hasSize(2);
  }

  @Test
  @DisplayName("Should keep direct play when the viewer switches to the audio track that it plays")
  void shouldKeepDirectPlayWhenTheViewerSwitchesToTheAudioTrackThatItPlays() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);

    var unchanged = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 0)));

    assertThat(unchanged).isEqualTo(directPlay);
    assertThat(streams.createdCommands).hasSize(1);
  }

  // Only direct play moves for an audio switch. A second switch names the retired direct-play
  // stream session, so it starts nothing.
  @Test
  @DisplayName(
      "Should start one HLS format attempt when the viewer switches twice during direct play")
  void shouldStartOneHlsFormatAttemptWhenTheViewerSwitchesTwiceDuringDirectPlay() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    var first = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 1)));

    var second = accepted(service.switchAudioTrack(audioSwitch(directPlay, command, 0)));

    assertThat(second).isEqualTo(first);
    assertThat(streams.createdCommands).hasSize(2);
  }

  // The switch and a direct-play failure each move the same direct-play format attempt, so one HLS
  // format attempt plays and every other stream session ends, whichever comes first.
  @Test
  @DisplayName(
      "Should start one HLS format attempt when an audio switch and a direct-play failure race")
  void shouldStartOneHlsFormatAttemptWhenAnAudioSwitchAndADirectPlayFailureRace() throws Exception {
    var work = new ConcurrentLinkedQueue<Runnable>();
    var playback = serviceRunning(work::add).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(playback, command);
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var audioSwitch =
          threads.submit(
              () -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return playback.switchAudioTrack(audioSwitch(directPlay, command, 1));
              });
      var failure =
          threads.submit(
              () -> {
                ready.countDown();
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                return playback.reportFormatFailure(report(directPlay, command).build());
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      release.countDown();
      audioSwitch.get(5, TimeUnit.SECONDS);
      failure.get(5, TimeUnit.SECONDS);
    }

    drain(work);
    var hls = playback.access(request(directPlay, command)).orElseThrow();
    assertThat(hls.decisions()).extracting(PlaybackDecision::formatAttempt).containsExactly(1, 2);
    assertThat(hls.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            attempt -> assertThat(streams.getAllSessions()).containsExactly(attempt.session()));
  }

  @Test
  @DisplayName("Should leave an HLS format attempt as it is when the viewer switches audio tracks")
  void shouldLeaveAnHlsFormatAttemptAsItIsWhenTheViewerSwitchesAudioTracks() {
    streams.sourceProbe = probeWithTwoAudioTracks();
    var command = startCommand().build();
    var hls = accepted(service.start(command));

    var unchanged = accepted(service.switchAudioTrack(audioSwitch(hls, command, 1)));

    assertThat(unchanged).isEqualTo(hls);
    assertThat(streams.createdCommands).hasSize(1);
  }

  @Test
  @DisplayName("Should report a closed playback session when the viewer switches after it ended")
  void shouldReportAClosedPlaybackSessionWhenTheViewerSwitchesAfterItEnded() {
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlayOfTwoAudioTracks(service, command);
    service.close(request(directPlay, command));

    var outcome = service.switchAudioTrack(audioSwitch(directPlay, command, 1));

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new AudioTrackSwitchRejection.PlaybackSessionClosed()));
  }

  private PlaybackSessionSnapshot startDirectPlayOfTwoAudioTracks(
      PlaybackSessionService playback, StartPlaybackSessionCommand command) {
    streams.sourceProbe = probeWithTwoAudioTracks();
    return startDirectPlay(playback, command);
  }

  private static MediaProbe probeWithTwoAudioTracks() {
    return defaultProbeBuilder()
        .streams(
            List.of(
                audioStreamBuilder("aac").index(1).language(Optional.of("eng")).build(),
                audioStreamBuilder("aac").index(2).language(Optional.of("fra")).build()))
        .build();
  }

  private SwitchPlaybackAudioTrackCommand audioSwitch(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command, int position) {
    return SwitchPlaybackAudioTrackCommand.builder()
        .playbackSessionId(snapshot.playbackSessionId())
        .streamSessionId(snapshot.currentFormatAttempt().streamSessionId())
        .identity(command.identity())
        .audioTrackPosition(position)
        .build();
  }

  private PlaybackSessionSnapshot startDirectPlay(
      PlaybackSessionService playback, StartPlaybackSessionCommand command) {
    streams.coversTheSourceForDirectPlay = true;
    var snapshot = accepted(playback.start(command));
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getDelivery()).isEqualTo(StreamDelivery.DIRECT_PLAY));
    assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    return snapshot;
  }

  // ADR 0052: a rejected Dolby Vision copy on a PQ or HLG base first falls back to the copy with
  // Dolby Vision stripped in every playback choice. Original and a fixed quality stay selected and
  // name the change; Auto's recoveries are not announced.
  @ParameterizedTest
  @CsvSource({
    "AUTO, PROFILE_8_1, DECODE_ERROR,",
    "AUTO, PROFILE_10_4, UNSUPPORTED_FORMAT,",
    "ORIGINAL, PROFILE_8_1, UNSUPPORTED_FORMAT, DOLBY_VISION_STRIPPED",
    "ORIGINAL, PROFILE_8_4, DECODE_ERROR, DOLBY_VISION_STRIPPED",
    "ORIGINAL, PROFILE_10_1, DECODE_ERROR, DOLBY_VISION_STRIPPED",
    "ORIGINAL, PROFILE_10_4, DECODE_ERROR, DOLBY_VISION_STRIPPED",
    "HIGH_720P, PROFILE_8_4, UNSUPPORTED_FORMAT, DOLBY_VISION_STRIPPED",
    "UHD_4K, PROFILE_10_1, DECODE_ERROR, DOLBY_VISION_STRIPPED"
  })
  @DisplayName(
      "Should play the copy with Dolby Vision stripped when the client rejects a Dolby Vision copy"
          + " on an HDR base")
  void shouldPlayTheCopyWithDolbyVisionStrippedWhenTheClientRejectsADolbyVisionCopyOnAnHdrBase(
      VideoQuality quality,
      DolbyVisionProfile profile,
      PlaybackFormatFailure reason,
      PlaybackFormatChange expectedChange) {
    streams.copiedDolbyVision = Optional.of(profile);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var dolbyVisionCopy = accepted(playback.start(command));

    var stripped =
        playback
            .reportFormatFailure(report(dolbyVisionCopy, command).reason(reason).build())
            .orElseThrow();

    assertThat(stripped.formatAttemptCount()).isEqualTo(2);
    assertThat(stripped.playbackChoice()).isEqualTo(quality);
    assertThat(stripped.formatChange()).isEqualTo(Optional.ofNullable(expectedChange));
    assertThatTheCopyWithDolbyVisionStrippedPlays(stripped);
    assertThat(streams.getAllSessions())
        .singleElement()
        .extracting(StreamSession::getSessionId)
        .isEqualTo(stripped.currentFormatAttempt().streamSessionId());
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            next -> {
              assertThat(next.videoFormatConstraint())
                  .isEqualTo(VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE);
              assertThat(next.options().quality()).isEqualTo(quality);
            });
  }

  // The worker reports a conversion failure only from a copy that converts Dolby Vision profile 7
  // on a PQ base (streamarr/streamarr-server#651), so the failure names a Dolby Vision copy on an
  // HDR base even before the format attempt that it fails is ready, and the copy with Dolby Vision
  // stripped follows in every playback choice (ADR 0052).
  @ParameterizedTest
  @CsvSource({"AUTO,", "ORIGINAL, DOLBY_VISION_STRIPPED", "HIGH_720P, DOLBY_VISION_STRIPPED"})
  @DisplayName(
      "Should play the copy with Dolby Vision stripped when the conversion failure arrives before"
          + " creation returns")
  void shouldPlayTheCopyWithDolbyVisionStrippedWhenTheConversionFailureArrivesBeforeCreationReturns(
      VideoQuality quality, PlaybackFormatChange expectedChange) {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    streams.duringNextCreation =
        child ->
            playback.onDolbyVisionConversionFailed(
                new DolbyVisionConversionFailedEvent(
                    child.streamSessionId(), UUID.randomUUID(), "default"));
    var command = startCommand().options(qualityOptions(quality)).build();

    var stripped = accepted(playback.start(command));

    assertThat(stripped.formatAttemptCount()).isEqualTo(2);
    assertThat(stripped.playbackChoice()).isEqualTo(quality);
    assertThat(stripped.formatChange()).isEqualTo(Optional.ofNullable(expectedChange));
    assertThatTheCopyWithDolbyVisionStrippedPlays(stripped);
    assertThat(streams.createdCommands.getLast())
        .satisfies(
            next -> {
              assertThat(next.videoFormatConstraint())
                  .isEqualTo(VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE);
              assertThat(next.options().quality()).isEqualTo(quality);
            });
  }

  // Profiles 5 and 10.0 have no compatible base, so the server never strips their Dolby Vision.
  @ParameterizedTest
  @CsvSource({
    "AUTO, PROFILE_5, AUTO,",
    "ORIGINAL, PROFILE_5, AUTO, COPY_FAILED",
    "ORIGINAL, PROFILE_10_0, AUTO, COPY_FAILED",
    "FULL_HD_1080P, PROFILE_10_0, FULL_HD_1080P, COPY_FAILED"
  })
  @DisplayName(
      "Should start an encode as the second format attempt when the client rejects a Dolby Vision"
          + " copy without a compatible base")
  void shouldStartAnEncodeAsTheSecondFormatAttemptWhenTheClientRejectsADolbyVisionCopyWithoutABase(
      VideoQuality quality,
      DolbyVisionProfile profile,
      VideoQuality expectedChoice,
      PlaybackFormatChange expectedChange) {
    streams.copiedDolbyVision = Optional.of(profile);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var dolbyVisionCopy = accepted(playback.start(command));

    var encode =
        playback.reportFormatFailure(report(dolbyVisionCopy, command).build()).orElseThrow();

    assertThatAnEncodeIsTheFormatAttempt(encode, 2);
    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(encode.formatChange()).isEqualTo(Optional.ofNullable(expectedChange));
  }

  // ADR 0052: the copy with Dolby Vision stripped copies the same video stream, so it would fail as
  // a mismatched or short copy does. Those failures go straight to the encode.
  @ParameterizedTest
  @MethodSource("dolbyVisionCopyWorkerFailures")
  @DisplayName(
      "Should start an encode without the stripped copy when a Dolby Vision copy mismatches or runs"
          + " short")
  void shouldStartAnEncodeWithoutTheStrippedCopyWhenADolbyVisionCopyMismatchesOrRunsShort(
      VideoQuality quality,
      BiConsumer<PlaybackSessionService, UUID> failCopy,
      VideoQuality expectedChoice,
      Optional<PlaybackFormatChange> expectedChange) {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var dolbyVisionCopy = accepted(playback.start(command));

    failCopy.accept(playback, dolbyVisionCopy.currentFormatAttempt().streamSessionId());

    var encode = playback.access(request(dolbyVisionCopy, command)).orElseThrow();
    assertThatAnEncodeIsTheFormatAttempt(encode, 2);
    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(encode.formatChange()).isEqualTo(expectedChange);
    assertThat(encode.decisions().getLast().reasons()).containsExactly(DecisionReason.COPY_FAILED);
  }

  static Stream<Arguments> dolbyVisionCopyWorkerFailures() {
    BiConsumer<PlaybackSessionService, UUID> mismatch =
        (playback, copyId) ->
            playback.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(copyId, UUID.randomUUID(), "default"));
    BiConsumer<PlaybackSessionService, UUID> runShort =
        (playback, copyId) -> playback.onCopiedVariantRanShort(ranShort(copyId));
    var copyFailed = Optional.of(PlaybackFormatChange.COPY_FAILED);
    return Stream.of(
        Arguments.of(
            VideoQuality.AUTO,
            Named.of("output mismatch", mismatch),
            VideoQuality.AUTO,
            Optional.empty()),
        Arguments.of(
            VideoQuality.ORIGINAL,
            Named.of("output mismatch", mismatch),
            VideoQuality.AUTO,
            copyFailed),
        Arguments.of(
            VideoQuality.ORIGINAL, Named.of("short copy", runShort), VideoQuality.AUTO, copyFailed),
        Arguments.of(
            VideoQuality.HIGH_720P,
            Named.of("short copy", runShort),
            VideoQuality.HIGH_720P,
            copyFailed));
  }

  // ADR 0052: a format error during the copy with Dolby Vision stripped falls back to the encode,
  // as during any HLS copy: in Auto for Original, and at the same fixed quality.
  @ParameterizedTest
  @CsvSource({
    "AUTO, AUTO,",
    "ORIGINAL, AUTO, COPY_FAILED",
    "FULL_HD_1080P, FULL_HD_1080P, COPY_FAILED"
  })
  @DisplayName("Should reach an encode when the client rejects the copy with Dolby Vision stripped")
  void shouldReachAnEncodeWhenTheClientRejectsTheCopyWithDolbyVisionStripped(
      VideoQuality quality, VideoQuality expectedChoice, PlaybackFormatChange expectedChange) {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var dolbyVisionCopy = accepted(playback.start(command));
    var stripped =
        playback.reportFormatFailure(report(dolbyVisionCopy, command).build()).orElseThrow();

    var encode = playback.reportFormatFailure(report(stripped, command).build()).orElseThrow();

    assertThatTheCopyWithDolbyVisionStrippedPlays(stripped);
    assertThatAnEncodeIsTheFormatAttempt(encode, 3);
    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(encode.formatChange()).isEqualTo(Optional.ofNullable(expectedChange));
    assertThat(streams.createdCommands.getLast().options().quality()).isEqualTo(expectedChoice);
  }

  // ADR 0052: a step that the declaration or the workers rule out is skipped, so a rejected Dolby
  // Vision copy whose stripped copy is not eligible reaches the encode at once.
  @ParameterizedTest
  @CsvSource({
    "AUTO, AUTO,",
    "ORIGINAL, AUTO, COPY_FAILED",
    "MEDIUM_480P, MEDIUM_480P, COPY_FAILED"
  })
  @DisplayName(
      "Should encode the next format attempt when the copy with Dolby Vision stripped is not"
          + " eligible")
  void shouldEncodeTheNextFormatAttemptWhenTheCopyWithDolbyVisionStrippedIsNotEligible(
      VideoQuality quality, VideoQuality expectedChoice, PlaybackFormatChange expectedChange) {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(quality)).build();
    var dolbyVisionCopy = accepted(playback.start(command));
    streams.copiesTheSource = false;

    var encode =
        playback.reportFormatFailure(report(dolbyVisionCopy, command).build()).orElseThrow();

    assertThat(streams.createdCommands.getLast().videoFormatConstraint())
        .isEqualTo(VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE);
    assertThatAnEncodeIsTheFormatAttempt(encode, 2);
    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(encode.formatChange()).isEqualTo(Optional.ofNullable(expectedChange));
  }

  // The format attempt after a rejected Dolby Vision copy copies with Dolby Vision stripped, or
  // encodes when that copy is not eligible; either way the failed Dolby Vision copy is its cause.
  @ParameterizedTest
  @CsvSource({"true, COPY", "false, ENCODE"})
  @DisplayName(
      "Should name the failed Dolby Vision copy as the cause when the format attempt after it"
          + " strips Dolby Vision or encodes")
  void shouldNameTheFailedDolbyVisionCopyAsTheCauseWhenTheNextFormatAttemptStripsOrEncodes(
      boolean strippedCopyEligible, PlaybackDelivery expectedDelivery) {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(4).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var dolbyVisionCopy = accepted(playback.start(command));
    streams.copiesTheSource = strippedCopyEligible;

    var next = playback.reportFormatFailure(report(dolbyVisionCopy, command).build()).orElseThrow();

    assertThat(next.decisions())
        .extracting(decision -> decision.video().delivery(), PlaybackDecision::reasons)
        .containsExactly(
            tuple(PlaybackDelivery.COPY, List.of()),
            tuple(expectedDelivery, List.of(DecisionReason.DOLBY_VISION_COPY_FAILED)));
  }

  // ADR 0052: four format attempts hold Original's direct play, Dolby Vision copy, stripped copy
  // and encode.
  @Test
  @DisplayName(
      "Should reach an encode in Auto from direct play when the default budget holds every Dolby"
          + " Vision step")
  void shouldReachAnEncodeInAutoFromDirectPlayWhenTheDefaultBudgetHoldsEveryDolbyVisionStep() {
    var steps = failDirectPlayThenTheDolbyVisionCopy(4);

    var encode =
        steps.playback().reportFormatFailure(report(steps.stripped(), steps.command()).build());

    assertThat(encode)
        .hasValueSatisfying(
            snapshot -> {
              assertThatAnEncodeIsTheFormatAttempt(snapshot, 4);
              assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
              assertThat(snapshot.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
            });
  }

  // ADR 0052: a budget of three ends Original's playback when the stripped copy fails.
  @Test
  @DisplayName(
      "Should end with attempts exhausted from direct play when a budget of three meets a failed"
          + " stripped copy")
  void shouldEndWithAttemptsExhaustedFromDirectPlayWhenABudgetOfThreeMeetsAFailedStrippedCopy() {
    var steps = failDirectPlayThenTheDolbyVisionCopy(3);

    var failed =
        steps.playback().reportFormatFailure(report(steps.stripped(), steps.command()).build());

    assertThat(failed)
        .hasValueSatisfying(
            snapshot ->
                assertThatAttemptsRanOutAfterTheStrippedCopy(snapshot, steps.stripped(), 3));
  }

  // The stripped copy is a format attempt of its own, so it needs room in the budget: under two,
  // direct play and the Dolby Vision copy use both.
  @Test
  @DisplayName(
      "Should end with attempts exhausted without a stripped copy when the Dolby Vision copy fails"
          + " with no attempt left")
  void
      shouldEndWithAttemptsExhaustedWithoutAStrippedCopyWhenTheDolbyVisionCopyFailsWithNoAttemptLeft() {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(service, command);
    var dolbyVisionCopy =
        service.reportFormatFailure(report(directPlay, command).build()).orElseThrow();

    var failed =
        service.reportFormatFailure(report(dolbyVisionCopy, command).build()).orElseThrow();

    assertThat(failed.formatAttemptCount()).isEqualTo(2);
    assertThat(failed.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(failed.formatChange()).isEmpty();
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                dolbyVisionCopy.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.AttemptsExhausted()));
    assertThat(streams.createdCommands)
        .extracting(CreateStreamSessionCommand::videoFormatConstraint)
        .doesNotContain(VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE);
    assertThat(streams.getAllSessions()).isEmpty();
  }

  // ADR 0052: a client changes its playback choice by creating a playback session. After Original
  // changed to Auto with the whole budget spent, the viewer's pick of Original starts afresh like
  // a reopened media title: it spends no format attempt and keeps none of the failures.
  @Test
  @DisplayName(
      "Should start Original afresh with the full budget when the viewer picks Original again after"
          + " the change to Auto")
  void
      shouldStartOriginalAfreshWithTheFullBudgetWhenTheViewerPicksOriginalAgainAfterTheChangeToAuto() {
    var steps = failDirectPlayThenTheDolbyVisionCopy(4);
    var encode =
        steps
            .playback()
            .reportFormatFailure(report(steps.stripped(), steps.command()).build())
            .orElseThrow();
    assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(encode.formatAttemptCount()).isEqualTo(4);

    var picked = startDirectPlay(steps.playback(), steps.command());
    steps.playback().close(request(encode, steps.command()));

    assertThat(picked.playbackSessionId()).isNotEqualTo(encode.playbackSessionId());
    assertThat(picked.formatAttemptCount()).isEqualTo(1);
    assertThat(picked.maximumFormatAttempts()).isEqualTo(4);
    assertThat(picked.formatChange()).isEmpty();
    assertThat(picked.decisions()).extracting(PlaybackDecision::formatAttempt).containsExactly(1);
    assertThat(streams.getAllSessions()).containsExactly(readySession(picked));
  }

  // ADR 0052: the earlier playback session's failures do not follow the pick, so every step that
  // failed before is tried again, in order, before the encode.
  @Test
  @DisplayName(
      "Should try the Dolby Vision copy, the stripped copy and the encode again when Original"
          + " picked again after the change to Auto fails")
  void shouldTryTheDolbyVisionCopyTheStrippedCopyAndTheEncodeAgainWhenOriginalPickedAgainFails() {
    var steps = failDirectPlayThenTheDolbyVisionCopy(4);
    var encode =
        steps
            .playback()
            .reportFormatFailure(report(steps.stripped(), steps.command()).build())
            .orElseThrow();
    var picked = startDirectPlay(steps.playback(), steps.command());
    steps.playback().close(request(encode, steps.command()));

    var dolbyVisionCopy =
        steps.playback().reportFormatFailure(report(picked, steps.command()).build()).orElseThrow();
    var stripped =
        steps
            .playback()
            .reportFormatFailure(report(dolbyVisionCopy, steps.command()).build())
            .orElseThrow();
    var encodeAgain =
        steps.playback().reportFormatFailure(report(stripped, steps.command()).build());

    assertThat(readySession(dolbyVisionCopy).getTranscodeDecision().videoDolbyVisionOutput())
        .isPresent();
    assertThat(dolbyVisionCopy.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThatTheCopyWithDolbyVisionStrippedPlays(stripped);
    assertThat(stripped.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(encodeAgain)
        .hasValueSatisfying(
            snapshot -> {
              assertThatAnEncodeIsTheFormatAttempt(snapshot, 4);
              assertThat(snapshot.playbackChoice()).isEqualTo(VideoQuality.AUTO);
            });
  }

  private record DolbyVisionSteps(
      PlaybackSessionService playback,
      StartPlaybackSessionCommand command,
      PlaybackSessionSnapshot stripped) {}

  // Original direct-plays a profile 8.1 file, and the client rejects direct play and then the Dolby
  // Vision copy, so the stripped copy plays as the third format attempt.
  private DolbyVisionSteps failDirectPlayThenTheDolbyVisionCopy(int maximum) {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var playback = serviceRunning(Runnable::run).maximumFormatAttempts(maximum).build();
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var directPlay = startDirectPlay(playback, command);
    var dolbyVisionCopy =
        playback.reportFormatFailure(report(directPlay, command).build()).orElseThrow();
    assertThat(dolbyVisionCopy.formatChange()).isEmpty();
    assertThat(dolbyVisionCopy.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready ->
                assertThat(ready.session().getTranscodeDecision().videoDolbyVisionOutput())
                    .isPresent());
    var stripped =
        playback.reportFormatFailure(report(dolbyVisionCopy, command).build()).orElseThrow();
    assertThat(stripped.formatAttemptCount()).isEqualTo(3);
    assertThat(stripped.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThatTheCopyWithDolbyVisionStrippedPlays(stripped);
    return new DolbyVisionSteps(playback, command, stripped);
  }

  // ADR 0052: with two format attempts, the stripped copy is the last one.
  @Test
  @DisplayName(
      "Should end with attempts exhausted when the stripped copy fails under a budget of two")
  void shouldEndWithAttemptsExhaustedWhenTheStrippedCopyFailsUnderABudgetOfTwo() {
    streams.copiedDolbyVision = Optional.of(DolbyVisionProfile.PROFILE_8_1);
    var command = startCommand().options(qualityOptions(VideoQuality.ORIGINAL)).build();
    var dolbyVisionCopy = accepted(service.start(command));
    var stripped =
        service.reportFormatFailure(report(dolbyVisionCopy, command).build()).orElseThrow();

    var failed = service.reportFormatFailure(report(stripped, command).build()).orElseThrow();

    assertThatTheCopyWithDolbyVisionStrippedPlays(stripped);
    assertThatAttemptsRanOutAfterTheStrippedCopy(failed, stripped, 2);
  }

  private void assertThatTheCopyWithDolbyVisionStrippedPlays(PlaybackSessionSnapshot snapshot) {
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              var decision = ready.session().getTranscodeDecision();
              assertThat(decision.transcodeMode().copiesVideo()).isTrue();
              assertThat(decision.videoBitstreamRewrites())
                  .containsExactly(BitstreamRewrite.STRIP_DOLBY_VISION);
              assertThat(decision.videoDolbyVisionOutput()).isEmpty();
            });
  }

  private void assertThatAnEncodeIsTheFormatAttempt(
      PlaybackSessionSnapshot snapshot, int formatAttemptCount) {
    assertThat(snapshot.formatAttemptCount()).isEqualTo(formatAttemptCount);
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Ready.class,
            ready -> {
              assertThat(ready.session().getTranscodeDecision().transcodeMode().copiesVideo())
                  .isFalse();
              assertThat(streams.getAllSessions()).containsExactly(ready.session());
            });
  }

  private void assertThatAttemptsRanOutAfterTheStrippedCopy(
      PlaybackSessionSnapshot failed, PlaybackSessionSnapshot stripped, int maximum) {
    assertThat(failed.formatAttemptCount()).isEqualTo(maximum);
    assertThat(failed.playbackChoice()).isEqualTo(VideoQuality.ORIGINAL);
    assertThat(failed.formatChange()).contains(PlaybackFormatChange.DOLBY_VISION_STRIPPED);
    assertThat(failed.currentFormatAttempt())
        .isEqualTo(
            new PlaybackFormatAttempt.Failed(
                stripped.currentFormatAttempt().streamSessionId(),
                new PlaybackSessionFailure.AttemptsExhausted()));
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
    copy.tryMarkShortCopy(new ShortCopy(3, Duration.ofSeconds(24), true));
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(Runnable::run)
            .maximumFormatAttempts(maximum);

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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(3)
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(
                _ -> {
                  throw new RejectedExecutionException();
                })
            .maximumFormatAttempts(2)
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
    service.reapExpired(clock.instant(), RETENTION);
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
            .clock(clock)
            .build();
    var command = startCommand().build();
    var initial = accepted(playback.start(command));
    playback.reportFormatFailure(report(initial, command).build());
    clock.advance(Duration.ofHours(25));
    playback.reapExpired(clock.instant(), RETENTION);
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
    service.reapExpired(clock.instant(), RETENTION);
    assertThat(service.access(request(initial, command))).contains(initial);
    clock.advance(Duration.ofHours(25));
    service.reapExpired(clock.instant(), RETENTION);
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .executor(work::add)
            .maximumFormatAttempts(2)
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
            .eventPublisher(_ -> {})
            .streamingService(streams)
            .authorityGate(authority)
            .maximumFormatAttempts(2)
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

  private static StreamSession readySession(PlaybackSessionSnapshot snapshot) {
    return ((PlaybackFormatAttempt.Ready) snapshot.currentFormatAttempt()).session();
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
        .eventPublisher(_ -> {})
        .streamingService(streams)
        .authorityGate(authority)
        .executor(executor)
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

  private static <R> PlaybackSessionSnapshot accepted(Outcome<PlaybackSessionSnapshot, R> outcome) {
    return outcome.fold(
        result -> result,
        rejections -> {
          throw new AssertionError(rejections);
        });
  }

  private static final QualityVariant ENCODED_720P =
      defaultVariantBuilder()
          .width(1280)
          .height(720)
          .videoBitrate(3_000_000L)
          .label("720p")
          .build();

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
    // Whether the declaration covers the source for direct play, as the transcode decision finds.
    private boolean coversTheSourceForDirectPlay;
    private List<DecisionReason> copyReasons = List.of();
    private List<DecisionReason> encodeReasons = List.of();
    private MediaProbe sourceProbe = defaultProbeBuilder().build();
    // The Dolby Vision that a copy keeps when the format attempt may keep it, as the transcode
    // decision finds for a client that declares the profile.
    private Optional<DolbyVisionProfile> copiedDolbyVision = Optional.empty();

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

      var session =
          sessionFor(command)
              .sessionId(command.streamSessionId())
              .mediaFileId(command.mediaFileId())
              .authority(command.identity().playbackAuthority())
              .options(command.options())
              .mediaProbe(sourceProbe)
              .startPosition(command.startPosition().orElse(null))
              .build();
      sessions.put(session.getSessionId(), session);
      if (duringNextCreation != null) {
        var callback = duringNextCreation;
        duringNextCreation = null;
        callback.accept(command);
      }

      return Outcome.accepted(session);
    }

    // A direct-play stream session carries no transcode decision, because nothing transcodes.
    private StreamSession.StreamSessionBuilder sessionFor(CreateStreamSessionCommand command) {
      var constraint = command.videoFormatConstraint();
      if (coversTheSourceForDirectPlay
          && constraint == VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE) {
        return directPlaySessionBuilder()
            .transcodeDecision(null)
            .directPlayAudioTrackPosition(sourceProbe.sessionAudioTrackPosition());
      }

      if (constraint == VideoFormatConstraint.ENCODE_ONLY || !copiesTheSource) {
        return defaultSessionBuilder()
            .transcodeDecision(
                fullTranscodeDecision("h264").toBuilder().reasons(encodeReasons).build())
            .variants(List.of(ENCODED_720P));
      }

      if (constraint == VideoFormatConstraint.COPY_WITH_DOLBY_VISION_STRIPPED_OR_ENCODE) {
        return defaultSessionBuilder()
            .transcodeDecision(
                remuxDecisionBuilder()
                    .videoBitstreamRewrites(List.of(BitstreamRewrite.STRIP_DOLBY_VISION))
                    .reasons(copyReasons)
                    .build())
            .variants(List.of());
      }

      var copy =
          copiedDolbyVision
              .map(ChildStreams::keepingDolbyVision)
              .orElseGet(StreamSessionFixture::remuxDecisionBuilder);
      return defaultSessionBuilder()
          .transcodeDecision(copy.reasons(copyReasons).build())
          .variants(List.of());
    }

    // A copy that keeps Dolby Vision copies a source that carries it, on the profile's base.
    private static TranscodeDecision.TranscodeDecisionBuilder keepingDolbyVision(
        DolbyVisionProfile profile) {
      var range =
          new VideoDynamicRange(
              profile.compatibleBase(),
              Presence.ABSENT,
              new DolbyVision.Present(Optional.empty(), Presence.PRESENT));
      var facts =
          VideoPlaybackFactsFixture.sdrH264FactsBuilder()
              .streamIndex(0)
              .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
              .dynamicRange(range)
              .build();
      return remuxDecisionBuilder()
          .videoSource(Optional.of(facts))
          .videoDolbyVisionOutput(Optional.of(new DolbyVisionOutput(profile, 6)));
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
    public boolean destroySessionIfLastAccessedBefore(UUID sessionId, Instant cutoff) {
      throw new UnsupportedOperationException();
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
