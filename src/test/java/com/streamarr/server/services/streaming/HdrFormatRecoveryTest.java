package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.PlaybackProbeServiceFixture.playbackProbeServiceBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultPlaybackAuthorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.identityFor;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.streaming.BitstreamRewrite;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionOutput;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.DynamicRangeChoice;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamDelivery;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.domain.streaming.WorkerVerifiedEncoder;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.UploadBudgetFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import com.streamarr.server.services.streaming.events.DolbyVisionConversionFailedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Format recovery from a rejected HDR output through the real transcode decision and playlist (ADR
 * 0052): a Dolby Vision copy on a PQ or HLG base falls back to the copy with Dolby Vision stripped,
 * an HDR copy falls back to an encode that keeps HDR only when a worker encodes it (#397), and Auto
 * gives HDR up for SDR after an HDR encode fails.
 */
@Tag("UnitTest")
@DisplayName("HDR Format Recovery Tests")
class HdrFormatRecoveryTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder()
          .maxConcurrentTranscodes(3)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .build();
  private static final int SOURCE_LEVEL = 6;
  private static final Set<String> ENCODED_CODEC_FAMILIES = Set.of("h264", "av1");
  private static final Pattern VIDEO_RANGE_ATTRIBUTE = Pattern.compile("VIDEO-RANGE=(\\w+)");
  // SVT-AV1 writes 10-bit, PQ, HLG and Dolby Vision reshaped into PQ, and libx264 writes 8-bit SDR.
  private static final List<WorkerVerifiedEncoder> HDR_ENCODERS =
      List.of(
          encoder("av1", "libsvtav1", Set.of(VideoEncodeFeature.values())),
          encoder("h264", "libx264", Set.of()));
  private static final Pattern AV1_CODEC_BIT_DEPTH =
      Pattern.compile("av01\\.\\d\\.\\d\\d[MH]\\.(\\d\\d)");

  private final FakeMediaFileRepository mediaFiles = new FakeMediaFileRepository();
  private final FakeTranscodeExecutor workers = new FakeTranscodeExecutor();
  private final FakeMediaFileContainerInfoRepository probes =
      new FakeMediaFileContainerInfoRepository();
  private PlaybackSessionService playback;

  @BeforeEach
  void setUp() {
    workers.setEncodableVideoCodecFamilies(ENCODED_CODEC_FAMILIES);
    workers.setVerifiedVideoCodecFamiliesByWorker(
        List.of(
            WorkerVerifiedCodecFamilies.builder()
                .workerId(UUID.randomUUID())
                .codecFamilies(ENCODED_CODEC_FAMILIES)
                .build()));
    workers.setVideoCopySupportByWorker(List.of(workerWithRewrites(BitstreamRewrite.values())));
    playback =
        PlaybackSessionService.builder()
            .eventPublisher(_ -> {})
            .streamingService(streaming())
            .authorityGate(new FakePlaybackAuthorityGate())
            .executor(Runnable::run)
            .maximumFormatAttempts(4)
            .build();
  }

  // ADR 0052: the stripped copy plays the source's own video on its PQ or HLG base, so Original and
  // a fixed quality stay selected and name the change, and the playlist announces the base alone.
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("dolbyVisionCopiesOnAnHdrBase")
  @DisplayName(
      "Should play the copy with Dolby Vision stripped on the source's base when the client rejects"
          + " the Dolby Vision copy")
  void
      shouldPlayTheCopyWithDolbyVisionStrippedOnTheSourcesBaseWhenTheClientRejectsTheDolbyVisionCopy(
          DolbyVisionProfile profile,
          VideoQuality quality,
          VideoRange base,
          Optional<PlaybackFormatChange> expectedChange) {
    probes.setDefaultProbe(dolbyVisionSource(profile).build());
    var command = start(quality);
    var dolbyVisionCopy = accepted(playback.start(command));

    var stripped = rejectCurrentFormatAttempt(dolbyVisionCopy, command);

    assertThat(decisionOf(dolbyVisionCopy).videoDolbyVisionOutput())
        .contains(new DolbyVisionOutput(profile, SOURCE_LEVEL));
    assertThat(stripped.formatAttemptCount()).isEqualTo(2);
    assertThat(stripped.playbackChoice()).isEqualTo(quality);
    assertThat(stripped.formatChange()).isEqualTo(expectedChange);
    assertThat(workers.getStartedRequests().getLast().transcodeDecision().plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            copy -> {
              assertThat(copy.bitstreamRewrites())
                  .containsExactly(BitstreamRewrite.STRIP_DOLBY_VISION);
              assertThat(copy.dolbyVisionOutput()).isEmpty();
              assertThat(copy.dynamicRange().dolbyVision()).isInstanceOf(DolbyVision.Absent.class);
            });
    assertThat(sessionOf(stripped).getVideoRange()).isEqualTo(base);
    assertThat(multivariantPlaylist(stripped))
        .contains(",VIDEO-RANGE=" + base.name())
        .doesNotContain("SUPPLEMENTAL-CODECS");
  }

  static Stream<Arguments> dolbyVisionCopiesOnAnHdrBase() {
    var stripped = Optional.of(PlaybackFormatChange.DOLBY_VISION_STRIPPED);
    return Stream.of(
        Arguments.of(
            DolbyVisionProfile.PROFILE_8_1, VideoQuality.AUTO, VideoRange.PQ, Optional.empty()),
        Arguments.of(
            DolbyVisionProfile.PROFILE_8_1, VideoQuality.ORIGINAL, VideoRange.PQ, stripped),
        Arguments.of(
            DolbyVisionProfile.PROFILE_8_4, VideoQuality.ORIGINAL, VideoRange.HLG, stripped),
        Arguments.of(
            DolbyVisionProfile.PROFILE_8_4, VideoQuality.FULL_HD_1080P, VideoRange.HLG, stripped),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_1, VideoQuality.FULL_HD_1080P, VideoRange.PQ, stripped),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_4, VideoQuality.ORIGINAL, VideoRange.HLG, stripped),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_4, VideoQuality.AUTO, VideoRange.HLG, Optional.empty()));
  }

  // streamarr/streamarr-server#651: a single-track profile 7 source copies as profile 8.1 for a
  // client that declares profile 8.1, with the conversion and the normalized record in its decision
  // and profile 8.1 in its playlist entry. When the worker cannot convert a picture, the copy with
  // Dolby Vision stripped follows and the playback choice stays (#401, ADR 0052).
  @ParameterizedTest(name = "{0}")
  @MethodSource("qualitiesAfterAStrippedCopy")
  @DisplayName(
      "Should convert single-track profile 7 to profile 8.1 and play the copy with Dolby Vision"
          + " stripped when the worker fails the conversion")
  void
      shouldConvertSingleTrackProfile7ToProfile81AndPlayTheCopyWithDolbyVisionStrippedWhenTheWorkerFailsTheConversion(
          VideoQuality quality, Optional<PlaybackFormatChange> expectedChange) {
    probes.setDefaultProbe(singleTrackProfile7Source().build());
    var command = start(quality);
    var converted = accepted(playback.start(command));

    playback.onDolbyVisionConversionFailed(
        new DolbyVisionConversionFailedEvent(
            converted.currentFormatAttempt().streamSessionId(), UUID.randomUUID(), "default"));

    var stripped =
        playback
            .access(
                PlaybackSessionRequest.builder()
                    .playbackSessionId(converted.playbackSessionId())
                    .identity(command.identity())
                    .build())
            .orElseThrow();
    assertThat(decisionOf(converted).videoBitstreamRewrites())
        .containsExactly(
            BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
            BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
    assertThat(decisionOf(converted).videoDolbyVisionOutput())
        .contains(new DolbyVisionOutput(DolbyVisionProfile.PROFILE_8_1, SOURCE_LEVEL));
    assertThat(multivariantPlaylist(converted))
        .contains(",VIDEO-RANGE=PQ")
        .contains("SUPPLEMENTAL-CODECS=\"dvh1.08.06/db1p\"");
    assertThat(stripped.formatAttemptCount()).isEqualTo(2);
    assertThat(stripped.playbackChoice()).isEqualTo(quality);
    assertThat(stripped.formatChange()).isEqualTo(expectedChange);
    assertThat(decisionOf(stripped).videoBitstreamRewrites())
        .containsExactly(BitstreamRewrite.STRIP_DOLBY_VISION);
    assertThat(decisionOf(stripped).videoDolbyVisionOutput()).isEmpty();
    assertThat(multivariantPlaylist(stripped)).doesNotContain("SUPPLEMENTAL-CODECS");
  }

  // streamarr/streamarr-server#651 and ADR 0052: the worker's typed conversion failure repeats in
  // every attempt of the same Dolby Vision copy, so the copy moves to the copy with Dolby Vision
  // stripped, as the client's rejection does, and Original and a fixed quality stay selected.
  @ParameterizedTest(name = "{0}")
  @MethodSource("qualitiesAfterAStrippedCopy")
  @DisplayName(
      "Should play the copy with Dolby Vision stripped when the worker fails the Dolby Vision"
          + " copy's conversion")
  void shouldPlayTheCopyWithDolbyVisionStrippedWhenTheWorkerFailsTheDolbyVisionCopysConversion(
      VideoQuality quality, Optional<PlaybackFormatChange> expectedChange) {
    probes.setDefaultProbe(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).build());
    var command = start(quality);
    var dolbyVisionCopy = accepted(playback.start(command));

    playback.onDolbyVisionConversionFailed(
        new DolbyVisionConversionFailedEvent(
            dolbyVisionCopy.currentFormatAttempt().streamSessionId(),
            UUID.randomUUID(),
            "default"));

    var stripped =
        playback
            .access(
                PlaybackSessionRequest.builder()
                    .playbackSessionId(dolbyVisionCopy.playbackSessionId())
                    .identity(command.identity())
                    .build())
            .orElseThrow();
    assertThat(stripped.formatAttemptCount()).isEqualTo(2);
    assertThat(stripped.playbackChoice()).isEqualTo(quality);
    assertThat(stripped.formatChange()).isEqualTo(expectedChange);
    assertThat(decisionOf(stripped).videoBitstreamRewrites())
        .containsExactly(BitstreamRewrite.STRIP_DOLBY_VISION);
    assertThat(decisionOf(stripped).videoDolbyVisionOutput()).isEmpty();
  }

  static Stream<Arguments> qualitiesAfterAStrippedCopy() {
    var stripped = Optional.of(PlaybackFormatChange.DOLBY_VISION_STRIPPED);
    return Stream.of(
        Arguments.of(VideoQuality.AUTO, Optional.empty()),
        Arguments.of(VideoQuality.ORIGINAL, stripped),
        Arguments.of(VideoQuality.FULL_HD_1080P, stripped));
  }

  // ADR 0036: a worker that cannot strip Dolby Vision cannot deliver the stripped copy, so the
  // recovery skips it and encodes, and Original's encode plays in Auto (ADR 0052).
  @Test
  @DisplayName(
      "Should encode in Auto when Original's Dolby Vision copy fails and no connected worker strips"
          + " Dolby Vision")
  void shouldEncodeInAutoWhenOriginalsDolbyVisionCopyFailsAndNoConnectedWorkerStripsDolbyVision() {
    workers.setVideoCopySupportByWorker(
        List.of(workerWithRewrites(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD)));
    probes.setDefaultProbe(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).build());
    var command = start(VideoQuality.ORIGINAL);
    var dolbyVisionCopy = accepted(playback.start(command));

    var encode = rejectCurrentFormatAttempt(dolbyVisionCopy, command);

    assertThat(decisionOf(dolbyVisionCopy).videoDolbyVisionOutput()).isPresent();
    assertThat(encode.formatAttemptCount()).isEqualTo(2);
    assertThat(encode.playbackChoice()).isEqualTo(VideoQuality.AUTO);
    assertThat(encode.formatChange()).contains(PlaybackFormatChange.COPY_FAILED);
    assertThat(decisionOf(encode).transcodeMode().copiesVideo()).isFalse();
  }

  // ADR 0052: a source without an output frame rate cannot be encoded (#446), so no encode can
  // replace its copies. A rejected Dolby Vision copy still falls back to the copy with Dolby Vision
  // stripped in every playback choice, and the rejected stripped copy ends playback with the typed
  // error.
  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "ORIGINAL", "FULL_HD_1080P"})
  @DisplayName(
      "Should end with the typed error after the stripped copy when no encode can replace the"
          + " Dolby Vision copy")
  void shouldEndWithTheTypedErrorAfterTheStrippedCopyWhenNoEncodeCanReplaceTheDolbyVisionCopy(
      VideoQuality quality) {
    probes.setDefaultProbe(
        dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).frameRateUnknown(true).build());
    var command = start(quality);
    var dolbyVisionCopy = accepted(playback.start(command));
    var stripped = rejectCurrentFormatAttempt(dolbyVisionCopy, command);

    var failed = rejectCurrentFormatAttempt(stripped, command);

    assertThat(decisionOf(dolbyVisionCopy).videoDolbyVisionOutput()).isPresent();
    assertThat(decisionOf(stripped).videoBitstreamRewrites())
        .containsExactly(BitstreamRewrite.STRIP_DOLBY_VISION);
    assertThat(stripped.playbackChoice()).isEqualTo(quality);
    assertThat(failed.formatAttemptCount()).isEqualTo(3);
    assertThat(failed.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            attempt ->
                assertThat(attempt.reason())
                    .isEqualTo(
                        new PlaybackSessionFailure.CreationRejected(
                            new CreateStreamSessionRejection.FrameRateUnknown())));
  }

  // ADR 0052 and #401: no worker here encodes HDR (#397), so the encode that replaces a rejected
  // HDR copy says SDR in its decision, its playlist entry and the stream session's range, which the
  // server's answer carries for the client's HDR toggle.
  @ParameterizedTest(name = "{0} copy in {1}")
  @MethodSource("hdrCopies")
  @DisplayName(
      "Should replace a rejected HDR copy with an encode whose decision, playlist and range say"
          + " SDR")
  void shouldReplaceARejectedHdrCopyWithAnEncodeWhoseDecisionPlaylistAndRangeSaySdr(
      VideoRange copyRange, VideoQuality quality, Optional<PlaybackFormatChange> expectedChange) {
    probes.setDefaultProbe(
        sourceBuilder()
            .dynamicRange(
                VideoPlaybackFactsFixture.rangeWithoutEnhancement(
                    CompatibleBase.valueOf(copyRange.name())))
            .build());
    var command = start(quality);
    var hdrCopy = accepted(playback.start(command));

    var encode = rejectCurrentFormatAttempt(hdrCopy, command);

    assertThat(sessionOf(hdrCopy).getVideoRange()).isEqualTo(copyRange);
    assertThat(multivariantPlaylist(hdrCopy)).contains(",VIDEO-RANGE=" + copyRange.name());
    assertThat(encode.formatAttemptCount()).isEqualTo(2);
    assertThat(encode.playbackChoice()).isEqualTo(quality);
    assertThat(encode.formatChange()).isEqualTo(expectedChange);
    var decision = decisionOf(encode);
    assertThat(decision.transcodeMode().copiesVideo()).isFalse();
    assertThat(decision.videoRange()).isEqualTo(VideoRange.SDR);
    assertThat(workers.getStartedRequests().getLast().transcodeDecision().plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.Encode.class,
            output ->
                assertThat(output.dynamicRange().compatibleBase()).isEqualTo(CompatibleBase.SDR));
    assertThat(sessionOf(encode).getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(multivariantPlaylist(encode))
        .contains("#EXT-X-STREAM-INF:")
        .doesNotContain("VIDEO-RANGE=")
        .doesNotContain("SUPPLEMENTAL-CODECS");
  }

  static Stream<Arguments> hdrCopies() {
    var copyFailed = Optional.of(PlaybackFormatChange.COPY_FAILED);
    return Stream.of(
        Arguments.of(VideoRange.PQ, VideoQuality.AUTO, Optional.empty()),
        Arguments.of(VideoRange.HLG, VideoQuality.AUTO, Optional.empty()),
        Arguments.of(VideoRange.PQ, VideoQuality.FULL_HD_1080P, copyFailed),
        Arguments.of(VideoRange.HLG, VideoQuality.FULL_HD_1080P, copyFailed));
  }

  // ADR 0013 and ADR 0052: the server never silently replaces a manual choice. The viewer rejects
  // every format attempt until playback ends. Each scenario lists every format attempt that the
  // server delivers: the playback choice, what plays, the range that the answer, the decision and
  // the playlist report, the change that the answer names, and the picture.
  @ParameterizedTest(name = "{0}")
  @MethodSource("manualChoices")
  @DisplayName(
      "Should deliver what the manual choice promises or name the change in every format attempt"
          + " until playback ends")
  void shouldDeliverWhatTheManualChoicePromisesOrNameTheChangeInEveryFormatAttemptUntilPlaybackEnds(
      FormatAttemptScenario scenario) {
    assertThatEveryFormatAttemptDeliversTheScenario(scenario);
  }

  // In Auto, a failed encode falls back to the encode in another codec that the client and a worker
  // share, and playback ends with the typed error when none is left or the budget of four is spent
  // (#401). A fixed quality changes format once (ADR 0052).
  static Stream<FormatAttemptScenario> manualChoices() {
    var original = VideoQuality.ORIGINAL;
    var fullHd = VideoQuality.FULL_HD_1080P;
    var hd = VideoQuality.HIGH_720P;
    var copyFailed = PlaybackFormatChange.COPY_FAILED;
    var stripped = PlaybackFormatChange.DOLBY_VISION_STRIPPED;
    var exhausted = new PlaybackSessionFailure.AttemptsExhausted();
    var noEligibleFormat =
        new PlaybackSessionFailure.CreationRejected(
            new CreateStreamSessionRejection.NoEligibleVideoFormat());
    var fixedFailed =
        new PlaybackSessionFailure.FixedFormatFailed(PlaybackFormatFailure.DECODE_ERROR);
    return Stream.of(
        scenario("HDR10 in Original")
            .source(hdr10())
            .quality(original)
            .attempt(delivered(Output.COPY, VideoRange.PQ).choice(original).build())
            .attempt(autoEncode().changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).codec("av1").build())
            .end(noEligibleFormat)
            .build(),
        scenario("HLG in Original")
            .source(hlg())
            .quality(original)
            .attempt(delivered(Output.COPY, VideoRange.HLG).choice(original).build())
            .attempt(autoEncode().changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).codec("av1").build())
            .end(noEligibleFormat)
            .build(),
        scenario("10-bit SDR in Original")
            .source(tenBitSdr())
            .quality(original)
            .attempt(delivered(Output.COPY, VideoRange.SDR).choice(original).build())
            .attempt(autoEncode().changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).codec("av1").build())
            .end(noEligibleFormat)
            .build(),
        scenario("Dolby Vision 8.1 in Original")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).build())
            .quality(original)
            .attempt(delivered(Output.DOLBY_VISION_COPY, VideoRange.PQ).choice(original).build())
            .attempt(
                delivered(Output.STRIPPED_COPY, VideoRange.PQ)
                    .choice(original)
                    .changed(stripped)
                    .build())
            .attempt(autoEncode().changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).codec("av1").build())
            .end(exhausted)
            .build(),
        scenario("Dolby Vision 10.4 in Original")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_10_4).build())
            .quality(original)
            .attempt(
                delivered(Output.DOLBY_VISION_COPY, VideoRange.HLG)
                    .choice(original)
                    .codec("av1")
                    .build())
            .attempt(
                delivered(Output.STRIPPED_COPY, VideoRange.HLG)
                    .choice(original)
                    .changed(stripped)
                    .codec("av1")
                    .build())
            .attempt(autoEncode().changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).codec("av1").build())
            .end(exhausted)
            .build(),
        scenario("HDR10 in Original, direct play first")
            .source(hdr10())
            .quality(original)
            .client(dolbyVisionClient(List.of(SourceContainer.MATROSKA)))
            .attempt(delivered(Output.DIRECT_PLAY, VideoRange.PQ).choice(original).build())
            .attempt(delivered(Output.COPY, VideoRange.PQ).choice(original).build())
            .attempt(autoEncode().changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).codec("av1").build())
            .end(exhausted)
            .build(),
        scenario("Dolby Vision 8.1 in Original, direct play first")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).build())
            .quality(original)
            .client(dolbyVisionClient(List.of(SourceContainer.MATROSKA)))
            .attempt(delivered(Output.DIRECT_PLAY, VideoRange.PQ).choice(original).build())
            .attempt(delivered(Output.DOLBY_VISION_COPY, VideoRange.PQ).choice(original).build())
            .attempt(
                delivered(Output.STRIPPED_COPY, VideoRange.PQ)
                    .choice(original)
                    .changed(stripped)
                    .build())
            .attempt(autoEncode().changed(copyFailed).build())
            .end(exhausted)
            .build(),
        scenario("HDR10 in Original on a client without HDR")
            .source(hdr10())
            .quality(original)
            .client(sdrClient())
            .attempt(autoEncode().changed(PlaybackFormatChange.STARTED_WITH_ENCODE).build())
            .end(noEligibleFormat)
            .build(),
        scenario("HDR10 in 1080p, HDR chosen")
            .source(hdr10())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.HDR)
            .attempt(delivered(Output.COPY, VideoRange.PQ).choice(fullHd).build())
            .attempt(fixedEncode(fullHd).changed(copyFailed).build())
            .end(fixedFailed)
            .build(),
        scenario("HLG in 1080p, HDR chosen")
            .source(hlg())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.HDR)
            .attempt(delivered(Output.COPY, VideoRange.HLG).choice(fullHd).build())
            .attempt(fixedEncode(fullHd).changed(copyFailed).build())
            .end(fixedFailed)
            .build(),
        scenario("10-bit SDR in 1080p, HDR chosen")
            .source(tenBitSdr())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.HDR)
            .attempt(delivered(Output.COPY, VideoRange.SDR).choice(fullHd).build())
            .attempt(fixedEncode(fullHd).changed(copyFailed).build())
            .end(fixedFailed)
            .build(),
        scenario("Dolby Vision 8.1 in 1080p, HDR chosen")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).build())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.HDR)
            .attempt(delivered(Output.DOLBY_VISION_COPY, VideoRange.PQ).choice(fullHd).build())
            .attempt(
                delivered(Output.STRIPPED_COPY, VideoRange.PQ)
                    .choice(fullHd)
                    .changed(stripped)
                    .build())
            .attempt(fixedEncode(fullHd).changed(copyFailed).build())
            .end(fixedFailed)
            .build(),
        scenario("HDR10 in 720p, HDR chosen")
            .source(hdr10())
            .quality(hd)
            .rangeChoice(DynamicRangeChoice.HDR)
            .attempt(fixedEncode(hd).build())
            .end(fixedFailed)
            .build(),
        scenario("Dolby Vision 10.4 in 720p, HDR chosen")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_10_4).build())
            .quality(hd)
            .rangeChoice(DynamicRangeChoice.HDR)
            .attempt(fixedEncode(hd).build())
            .end(fixedFailed)
            .build(),
        scenario("HDR10 in 1080p, SDR chosen")
            .source(hdr10())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.SDR)
            .attempt(fixedEncode(fullHd).build())
            .end(fixedFailed)
            .build(),
        scenario("Dolby Vision 8.1 in 1080p, SDR chosen")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).build())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.SDR)
            .attempt(fixedEncode(fullHd).build())
            .end(fixedFailed)
            .build(),
        scenario("10-bit SDR in 1080p, SDR chosen")
            .source(tenBitSdr())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.SDR)
            .attempt(delivered(Output.COPY, VideoRange.SDR).choice(fullHd).build())
            .attempt(fixedEncode(fullHd).changed(copyFailed).build())
            .end(fixedFailed)
            .build(),
        scenario("HLG in 720p, SDR chosen")
            .source(hlg())
            .quality(hd)
            .rangeChoice(DynamicRangeChoice.SDR)
            .attempt(fixedEncode(hd).build())
            .end(fixedFailed)
            .build());
  }

  // #397 and #401: Auto may give up HDR for SDR as part of its bounded recovery, and only after an
  // HDR encode fails. A rejected HDR or 10-bit encode keeps its format out of every later format
  // attempt, and a rejected 8-bit SDR encode keeps its codec family out. A rejected copy falls back
  // to the best encode, HDR included. A manual choice never gives HDR up (ADR 0052). Each format
  // attempt's answer, decision and playlist describe the output that it plans.
  @ParameterizedTest(name = "{0}")
  @MethodSource("scenariosWithHdrEncodes")
  @DisplayName(
      "Should give up HDR for SDR only in Auto and only after an HDR encode fails when a worker"
          + " encodes HDR")
  void shouldGiveUpHdrForSdrOnlyInAutoAndOnlyAfterAnHdrEncodeFailsWhenAWorkerEncodesHdr(
      FormatAttemptScenario scenario) {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, HDR_ENCODERS);

    assertThatEveryFormatAttemptDeliversTheScenario(scenario);
  }

  static Stream<FormatAttemptScenario> scenariosWithHdrEncodes() {
    var auto = VideoQuality.AUTO;
    var original = VideoQuality.ORIGINAL;
    var fullHd = VideoQuality.FULL_HD_1080P;
    var hd = VideoQuality.HIGH_720P;
    var copyFailed = PlaybackFormatChange.COPY_FAILED;
    var exhausted = new PlaybackSessionFailure.AttemptsExhausted();
    var noEligibleFormat =
        new PlaybackSessionFailure.CreationRejected(
            new CreateStreamSessionRejection.NoEligibleVideoFormat());
    var fixedFailed =
        new PlaybackSessionFailure.FixedFormatFailed(PlaybackFormatFailure.DECODE_ERROR);
    return Stream.of(
        scenario("HDR10 in Auto")
            .source(hdr10())
            .quality(auto)
            .attempt(autoCopy(Output.COPY, VideoRange.PQ).build())
            .attempt(autoHdrEncode(VideoRange.PQ).build())
            .attempt(autoEncode().build())
            .attempt(autoEncode().codec("av1").build())
            .end(exhausted)
            .build(),
        scenario("HLG in Auto")
            .source(hlg())
            .quality(auto)
            .attempt(autoCopy(Output.COPY, VideoRange.HLG).build())
            .attempt(autoHdrEncode(VideoRange.HLG).build())
            .attempt(autoEncode().build())
            .attempt(autoEncode().codec("av1").build())
            .end(exhausted)
            .build(),
        scenario("10-bit SDR in Auto")
            .source(tenBitSdr())
            .quality(auto)
            .attempt(autoCopy(Output.COPY, VideoRange.SDR).build())
            .attempt(autoEncode().video("av1", 10).build())
            .attempt(autoEncode().build())
            .attempt(autoEncode().codec("av1").build())
            .end(exhausted)
            .build(),
        scenario("HDR10 in Auto on a client whose HEVC entry declares SDR alone")
            .source(hdr10())
            .quality(auto)
            .client(sdrHevcClient())
            .attempt(autoHdrEncode(VideoRange.PQ).build())
            .attempt(autoEncode().build())
            .attempt(autoEncode().codec("av1").build())
            .end(noEligibleFormat)
            .build(),
        scenario("Dolby Vision 5 in Auto on a client that declares PQ but not profile 5")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_5).build())
            .quality(auto)
            .attempt(autoHdrEncode(VideoRange.PQ).build())
            .attempt(autoEncode().build())
            .attempt(autoEncode().codec("av1").build())
            .end(noEligibleFormat)
            .build(),
        scenario("Dolby Vision 8.1 in Auto")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1).build())
            .quality(auto)
            .attempt(autoCopy(Output.DOLBY_VISION_COPY, VideoRange.PQ).variants(1).build())
            .attempt(autoCopy(Output.STRIPPED_COPY, VideoRange.PQ).build())
            .attempt(autoHdrEncode(VideoRange.PQ).build())
            .attempt(autoEncode().build())
            .end(exhausted)
            .build(),
        scenario("HDR10 in Original")
            .source(hdr10())
            .quality(original)
            .attempt(delivered(Output.COPY, VideoRange.PQ).choice(original).build())
            .attempt(autoHdrEncode(VideoRange.PQ).changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).build())
            .attempt(autoEncode().changed(copyFailed).codec("av1").build())
            .end(exhausted)
            .build(),
        scenario("HDR10 in 1080p, HDR chosen")
            .source(hdr10())
            .quality(fullHd)
            .rangeChoice(DynamicRangeChoice.HDR)
            .attempt(delivered(Output.COPY, VideoRange.PQ).choice(fullHd).build())
            .attempt(fixedHdrEncode(fullHd, VideoRange.PQ).changed(copyFailed).build())
            .end(fixedFailed)
            .build(),
        scenario("HDR10 in 720p, no HDR choice")
            .source(hdr10())
            .quality(hd)
            .attempt(fixedHdrEncode(hd, VideoRange.PQ).build())
            .end(fixedFailed)
            .build(),
        scenario("HLG in 720p, SDR chosen")
            .source(hlg())
            .quality(hd)
            .rangeChoice(DynamicRangeChoice.SDR)
            .attempt(fixedEncode(hd).build())
            .end(fixedFailed)
            .build());
  }

  // #397: the viewer's switch from Auto to a fixed quality starts a new playback session (ADR
  // 0052),
  // whose HDR choice is the range that plays, as the client's HDR toggle shows it. After Auto gave
  // up HDR, the fixed quality plays SDR where an HDR copy or encode would fit, and after Auto
  // played HDR, the fixed quality keeps HDR.
  @ParameterizedTest(name = "{0}")
  @MethodSource("switchesFromAuto")
  @DisplayName(
      "Should keep the picture that plays when the viewer switches from Auto to a fixed quality")
  void shouldKeepThePictureThatPlaysWhenTheViewerSwitchesFromAutoToAFixedQuality(
      SwitchFromAuto switchFromAuto) {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, HDR_ENCODERS);
    probes.setDefaultProbe(hdr10());
    var autoCommand =
        start(
            StreamingOptions.builder().quality(VideoQuality.AUTO).build(), switchFromAuto.client());
    var auto = accepted(playback.start(autoCommand));
    for (var rejection = 0; rejection < switchFromAuto.rejectionsInAuto(); rejection++) {
      auto = rejectCurrentFormatAttempt(auto, autoCommand);
    }

    var playing = deliveredAttempt(auto);
    playback.close(
        PlaybackSessionRequest.builder()
            .playbackSessionId(auto.playbackSessionId())
            .identity(autoCommand.identity())
            .build());

    var fixed =
        accepted(
            playback.start(
                start(
                    StreamingOptions.builder()
                        .quality(switchFromAuto.quality())
                        .dynamicRange(hdrToggleShowing(playing.range()))
                        .build(),
                    switchFromAuto.client())));

    assertThat(playing).isEqualTo(switchFromAuto.playingInAuto());
    assertThat(deliveredAttempt(fixed)).isEqualTo(switchFromAuto.afterTheSwitch());
  }

  static Stream<SwitchFromAuto> switchesFromAuto() {
    var fullHd = VideoQuality.FULL_HD_1080P;
    var hd = VideoQuality.HIGH_720P;
    return Stream.of(
        switchFromAuto("Auto gave up HDR after its HDR encode failed, then 1080p")
            .rejectionsInAuto(2)
            .quality(fullHd)
            .playingInAuto(autoEncode().build())
            .afterTheSwitch(fixedEncode(fullHd).build())
            .build(),
        switchFromAuto("Auto gave up HDR after its HDR encode failed, then 720p")
            .rejectionsInAuto(2)
            .quality(hd)
            .playingInAuto(autoEncode().build())
            .afterTheSwitch(fixedEncode(hd).build())
            .build(),
        switchFromAuto("Auto plays the HDR copy, then 1080p")
            .quality(fullHd)
            .playingInAuto(autoCopy(Output.COPY, VideoRange.PQ).build())
            .afterTheSwitch(delivered(Output.COPY, VideoRange.PQ).choice(fullHd).build())
            .build(),
        switchFromAuto("Auto plays the HDR copy, then 720p")
            .quality(hd)
            .playingInAuto(autoCopy(Output.COPY, VideoRange.PQ).build())
            .afterTheSwitch(fixedHdrEncode(hd, VideoRange.PQ).build())
            .build(),
        switchFromAuto("Auto plays an HDR encode, then 1080p")
            .client(sdrHevcClient())
            .quality(fullHd)
            .playingInAuto(autoHdrEncode(VideoRange.PQ).build())
            .afterTheSwitch(fixedHdrEncode(fullHd, VideoRange.PQ).build())
            .build(),
        switchFromAuto("Auto gave up HDR after its HDR encode failed, then 1080p without HEVC HDR")
            .client(sdrHevcClient())
            .rejectionsInAuto(1)
            .quality(fullHd)
            .playingInAuto(autoEncode().build())
            .afterTheSwitch(fixedEncode(fullHd).build())
            .build());
  }

  /**
   * The client's HDR toggle shows the range that plays, and a fixed quality sends it as the HDR
   * choice.
   */
  private static DynamicRangeChoice hdrToggleShowing(VideoRange playing) {
    if (playing == VideoRange.SDR) {
      return DynamicRangeChoice.SDR;
    }

    return DynamicRangeChoice.HDR;
  }

  @Builder(builderMethodName = "switchFromAutoBuilder")
  private record SwitchFromAuto(
      @NonNull String name,
      @NonNull ClientCapabilityDeclaration client,
      int rejectionsInAuto,
      @NonNull VideoQuality quality,
      @NonNull DeliveredAttempt playingInAuto,
      @NonNull DeliveredAttempt afterTheSwitch) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static SwitchFromAuto.SwitchFromAutoBuilder switchFromAuto(String name) {
    return SwitchFromAuto.switchFromAutoBuilder().name(name).client(dolbyVisionClient());
  }

  // The viewer rejects every format attempt until playback ends.
  private void assertThatEveryFormatAttemptDeliversTheScenario(FormatAttemptScenario scenario) {
    probes.setDefaultProbe(scenario.source());
    var command =
        start(
            StreamingOptions.builder()
                .quality(scenario.quality())
                .dynamicRange(scenario.rangeChoice())
                .build(),
            scenario.client());
    var delivered = new ArrayList<DeliveredAttempt>();
    var snapshot = accepted(playback.start(command));
    while (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready) {
      assertThat(snapshot.requestedChoice()).isEqualTo(scenario.quality());
      assertThatTheDecisionKeepsTheHdrChoice(snapshot, scenario.rangeChoice());
      delivered.add(deliveredAttempt(snapshot));
      snapshot = rejectCurrentFormatAttempt(snapshot, command);
    }

    assertThat(delivered).containsExactlyElementsOf(scenario.attempts());
    assertThat(snapshot.currentFormatAttempt())
        .isInstanceOfSatisfying(
            PlaybackFormatAttempt.Failed.class,
            failed -> assertThat(failed.reason()).isEqualTo(scenario.end()));
  }

  /** What one format attempt delivers, as the server's answer, decision and playlist report it. */
  private enum Output {
    DIRECT_PLAY,
    DOLBY_VISION_COPY,
    STRIPPED_COPY,
    COPY,
    ENCODE
  }

  @Builder
  private record DeliveredAttempt(
      @NonNull VideoQuality choice,
      @NonNull Output output,
      @NonNull VideoRange range,
      @NonNull Optional<PlaybackFormatChange> change,
      @NonNull String picture,
      int variants,
      @NonNull String codec,
      int bitDepth) {

    private static class DeliveredAttemptBuilder {
      DeliveredAttemptBuilder changed(PlaybackFormatChange formatChange) {
        return change(Optional.of(formatChange));
      }

      DeliveredAttemptBuilder video(String codecFamily, int videoBitDepth) {
        return codec(codecFamily).bitDepth(videoBitDepth);
      }
    }
  }

  @Builder(builderMethodName = "formatAttemptScenarioBuilder")
  private record FormatAttemptScenario(
      @NonNull String name,
      @NonNull MediaProbe source,
      @NonNull VideoQuality quality,
      DynamicRangeChoice rangeChoice,
      @NonNull ClientCapabilityDeclaration client,
      @Singular List<DeliveredAttempt> attempts,
      @NonNull PlaybackSessionFailure end) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static FormatAttemptScenario.FormatAttemptScenarioBuilder scenario(String name) {
    return FormatAttemptScenario.formatAttemptScenarioBuilder()
        .name(name)
        .client(dolbyVisionClient());
  }

  // The source's own 1080p 10-bit HEVC picture in one variant, with no change named. An encode's
  // picture is its top variant.
  private static DeliveredAttempt.DeliveredAttemptBuilder delivered(
      Output output, VideoRange range) {
    return DeliveredAttempt.builder()
        .output(output)
        .range(range)
        .change(Optional.empty())
        .picture("1920x1080")
        .variants(1)
        .video("hevc", 10);
  }

  // Auto's 8-bit H.264 SDR ladder for the 1080p source, topped by its 1080p variant (ADR 0050 and
  // ADR 0052).
  private static DeliveredAttempt.DeliveredAttemptBuilder autoEncode() {
    return delivered(Output.ENCODE, VideoRange.SDR)
        .choice(VideoQuality.AUTO)
        .variants(4)
        .video("h264", 8);
  }

  // Auto copies the source's own video above 720p, 480p and 360p in 10-bit AV1 in the copy's range,
  // which a worker here encodes and the client plays (ADR 0050). A copy that keeps Dolby Vision is
  // its playlist's one variant.
  private static DeliveredAttempt.DeliveredAttemptBuilder autoCopy(
      Output output, VideoRange range) {
    return delivered(output, range).choice(VideoQuality.AUTO).variants(4);
  }

  // Auto's 10-bit AV1 ladder in the source's PQ or HLG range for the 1080p source (#397).
  private static DeliveredAttempt.DeliveredAttemptBuilder autoHdrEncode(VideoRange range) {
    return autoEncode().range(range).video("av1", 10);
  }

  // A fixed quality's one 10-bit AV1 variant in the source's PQ or HLG range (#397).
  private static DeliveredAttempt.DeliveredAttemptBuilder fixedHdrEncode(
      VideoQuality quality, VideoRange range) {
    return fixedEncode(quality).range(range).video("av1", 10);
  }

  // A fixed quality's one SDR variant, which fills the quality's picture box (ADR 0050).
  private static DeliveredAttempt.DeliveredAttemptBuilder fixedEncode(VideoQuality quality) {
    var picture =
        switch (quality) {
          case FULL_HD_1080P -> "1920x1080";
          case HIGH_720P -> "1280x720";
          default -> throw new IllegalArgumentException("No fixture picture for " + quality);
        };
    return delivered(Output.ENCODE, VideoRange.SDR)
        .choice(quality)
        .picture(picture)
        .video("h264", 8);
  }

  // ADR 0013: the answer keeps the viewer's HDR choice beside the range that plays, so a client
  // that asked for HDR and receives SDR shows SDR in its HDR toggle with a notice.
  private static void assertThatTheDecisionKeepsTheHdrChoice(
      PlaybackSessionSnapshot snapshot, DynamicRangeChoice rangeChoice) {
    var session = sessionOf(snapshot);
    if (session.getDelivery() == StreamDelivery.DIRECT_PLAY) {
      return;
    }

    assertThat(session.getTranscodeDecision().dynamicRangeChoice())
        .as("the HDR choice on the decision")
        .isEqualTo(Optional.ofNullable(rangeChoice));
  }

  private static DeliveredAttempt deliveredAttempt(PlaybackSessionSnapshot snapshot) {
    var session = sessionOf(snapshot);
    if (session.getDelivery() == StreamDelivery.DIRECT_PLAY) {
      return DeliveredAttempt.builder()
          .choice(snapshot.playbackChoice())
          .output(Output.DIRECT_PLAY)
          .range(session.getVideoRange())
          .change(snapshot.formatChange())
          .picture(sourcePicture(session))
          .variants(1)
          .codec(session.getMediaProbe().videoCodec())
          .bitDepth(
              session.getMediaProbe().videoPlaybackFacts().orElseThrow().bitDepth().orElseThrow())
          .build();
    }

    var playlist = multivariantPlaylist(snapshot);
    var output = hlsOutputOf(session.getTranscodeDecision());
    var picture = sourcePicture(session);
    if (output == Output.ENCODE) {
      var top =
          session.getVariants().stream()
              .max(Comparator.comparingInt(QualityVariant::height))
              .orElseThrow();
      picture = top.width() + "x" + top.height();
    }

    return DeliveredAttempt.builder()
        .choice(snapshot.playbackChoice())
        .output(output)
        .range(announcedRange(session, playlist))
        .change(snapshot.formatChange())
        .picture(picture)
        .variants(playlist.split("#EXT-X-STREAM-INF:", -1).length - 1)
        .codec(session.getTranscodeDecision().videoCodecFamily())
        .bitDepth(announcedBitDepth(session, playlist))
        .build();
  }

  private static Output hlsOutputOf(TranscodeDecision decision) {
    if (!decision.transcodeMode().copiesVideo()) {
      return Output.ENCODE;
    }

    if (decision.videoDolbyVisionOutput().isPresent()) {
      return Output.DOLBY_VISION_COPY;
    }

    if (decision.videoBitstreamRewrites().contains(BitstreamRewrite.STRIP_DOLBY_VISION)) {
      return Output.STRIPPED_COPY;
    }

    return Output.COPY;
  }

  private static String sourcePicture(StreamSession session) {
    return session.getMediaProbe().width() + "x" + session.getMediaProbe().height();
  }

  // The client's HDR toggle shows the range that the answer reports, which must be the range that
  // the decision plans and every playlist entry announces (ADR 0052), a copy's lower variants
  // included (ADR 0050). A playlist without VIDEO-RANGE announces SDR (RFC 8216).
  private static VideoRange announcedRange(StreamSession session, String playlist) {
    var planned = session.getTranscodeDecision().videoRange();
    var announced =
        VIDEO_RANGE_ATTRIBUTE
            .matcher(playlist)
            .results()
            .map(match -> VideoRange.valueOf(match.group(1)))
            .collect(Collectors.toSet());
    assertThat(session.getVideoRange()).as("the answer's range").isEqualTo(planned);
    assertThat(announced)
        .as("the playlist's range")
        .isEqualTo(planned == VideoRange.SDR ? Set.of() : Set.of(planned));
    assertThat(playlist.lines().filter(line -> line.startsWith("#EXT-X-STREAM-INF:")))
        .as("the playlist entries that announce a range")
        .isNotEmpty()
        .allSatisfy(
            entry ->
                assertThat(VIDEO_RANGE_ATTRIBUTE.matcher(entry).find())
                    .isEqualTo(planned != VideoRange.SDR));
    return planned;
  }

  // The bit depth that the decision plans, which every AV1 codec string in the playlist must name,
  // those of a copy's lower variants included.
  private static int announcedBitDepth(StreamSession session, String playlist) {
    var planned =
        switch (session.getTranscodeDecision().plannedVideoOutput()) {
          case PlannedVideoOutput.Encode encode -> encode.bitDepth();
          case PlannedVideoOutput.StreamCopy copy -> copy.bitDepth().orElseThrow();
        };
    var announced =
        AV1_CODEC_BIT_DEPTH
            .matcher(playlist)
            .results()
            .map(match -> Integer.parseInt(match.group(1)))
            .collect(Collectors.toSet());
    assertThat(announced).as("the playlist's AV1 bit depths").isSubsetOf(planned);
    if (session.getTranscodeDecision().videoCodecFamily().equals("av1")) {
      assertThat(announced).as("the playlist's AV1 bit depth").containsExactly(planned);
    }

    return planned;
  }

  private static MediaProbe hdr10() {
    return sourceBuilder()
        .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
        .build();
  }

  private static MediaProbe hlg() {
    return sourceBuilder()
        .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.HLG))
        .build();
  }

  private static MediaProbe tenBitSdr() {
    return sourceBuilder()
        .dynamicRange(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.SDR))
        .build();
  }

  private PlaybackSessionSnapshot rejectCurrentFormatAttempt(
      PlaybackSessionSnapshot snapshot, StartPlaybackSessionCommand command) {
    return playback
        .reportFormatFailure(
            ReportPlaybackFormatFailureCommand.builder()
                .playbackSessionId(snapshot.playbackSessionId())
                .streamSessionId(snapshot.currentFormatAttempt().streamSessionId())
                .identity(command.identity())
                .reason(PlaybackFormatFailure.DECODE_ERROR)
                .build())
        .orElseThrow();
  }

  private static StreamSession sessionOf(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("Expected a ready format attempt: " + snapshot.currentFormatAttempt());
  }

  private static TranscodeDecision decisionOf(PlaybackSessionSnapshot snapshot) {
    return sessionOf(snapshot).getTranscodeDecision();
  }

  private static String multivariantPlaylist(PlaybackSessionSnapshot snapshot) {
    return new HlsPlaylistService(PROPERTIES, UploadBudgetFixture.OFF)
        .generateMultivariantPlaylist(sessionOf(snapshot), "t");
  }

  private StartPlaybackSessionCommand start(VideoQuality quality) {
    return start(StreamingOptions.builder().quality(quality).build(), dolbyVisionClient());
  }

  private StartPlaybackSessionCommand start(
      StreamingOptions options, ClientCapabilityDeclaration client) {
    var file =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/hdr.mkv")
                .filename("hdr.mkv")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(file.getId())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .options(options)
        .capabilityDeclaration(client)
        .networkRoute(NetworkRoute.INTERNET)
        .build();
  }

  private HlsStreamingService streaming() {
    var segments = new FakeSegmentStore();
    var registry = new FakeRuntimeStreamSessionRegistry();
    var meters = new SimpleMeterRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(workers)
            .segmentStore(segments)
            .properties(PROPERTIES)
            .runtimeRegistry(registry)
            .heldRequests(workers.heldSegmentRequests())
            .build();
    return HlsStreamingService.builder()
        .languagePreferences(noLanguagePreferences())
        .uploadBudget(UploadBudgetFixture.OFF)
        .mediaFileRepository(mediaFiles)
        .transcodeExecutor(workers)
        .segmentStore(segments)
        .playbackProbeService(playbackProbeServiceBuilder(probes).build())
        .transcodeDecisionService(new TranscodeDecisionService(PROPERTIES, meters))
        .qualityLadderService(new QualityLadderService())
        .properties(PROPERTIES)
        .authorityGate(new FakePlaybackAuthorityGate())
        .runtimeRegistry(registry)
        .producerLifecycle(rig.lifecycle())
        .deliveryCoordinator(rig.coordinator())
        .meterRegistry(meters)
        .build();
  }

  private static WorkerVideoCopySupport workerWithRewrites(BitstreamRewrite... rewrites) {
    return WorkerVideoCopySupport.builder()
        .plannedOutput(true)
        .bitstreamRewrites(Set.of(rewrites))
        .build();
  }

  // HEVC and AV1 entries that declare every base and profiles 8.1, 8.4, 10.1 and 10.4 up to level
  // 13, beside H.264 SDR and stereo AAC.
  private static ClientCapabilityDeclaration dolbyVisionClient() {
    return dolbyVisionClient(List.of());
  }

  // The same client, whose HEVC, AV1 and AAC entries also direct-play these containers.
  private static ClientCapabilityDeclaration dolbyVisionClient(
      List<SourceContainer> directPlayContainers) {
    var bases = List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);
    return defaultDeclarationBuilder()
        .video(
            List.of(
                hevcEntryBuilder()
                    .compatibleBases(bases)
                    .dolbyVisionProfiles(
                        profiles(DolbyVisionProfile.PROFILE_8_1, DolbyVisionProfile.PROFILE_8_4))
                    .directPlayContainers(directPlayContainers)
                    .build(),
                av1EntryBuilder()
                    .compatibleBases(bases)
                    .dolbyVisionProfiles(
                        profiles(DolbyVisionProfile.PROFILE_10_1, DolbyVisionProfile.PROFILE_10_4))
                    .directPlayContainers(directPlayContainers)
                    .build(),
                h264EntryBuilder().build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(directPlayContainers).build()))
        .build();
  }

  // HEVC and H.264 entries that declare SDR alone, beside stereo AAC.
  private static ClientCapabilityDeclaration sdrClient() {
    return defaultDeclarationBuilder()
        .video(List.of(hevcEntryBuilder().build(), h264EntryBuilder().build()))
        .build();
  }

  // The Dolby Vision client, whose HEVC entry declares SDR alone, so an HDR HEVC source is encoded.
  private static ClientCapabilityDeclaration sdrHevcClient() {
    var dolbyVisionClient = dolbyVisionClient();
    return defaultDeclarationBuilder()
        .audio(dolbyVisionClient.audio())
        .video(
            Stream.concat(
                    Stream.of(hevcEntryBuilder().build()),
                    dolbyVisionClient.video().stream().skip(1))
                .toList())
        .build();
  }

  private static WorkerVerifiedEncoder encoder(
      String codecFamily, String encoder, Set<VideoEncodeFeature> features) {
    return WorkerVerifiedEncoder.builder()
        .backend(
            EncoderBackend.builder()
                .codecFamily(codecFamily)
                .encoder(encoder)
                .kind(EncoderBackend.Kind.SOFTWARE)
                .build())
        .keyframeVerified(true)
        .encodeFeatures(features)
        .build();
  }

  private static List<DolbyVisionProfileCapability> profiles(DolbyVisionProfile... profiles) {
    return Stream.of(profiles)
        .map(
            profile -> DolbyVisionProfileCapability.builder().profile(profile).maxLevel(13).build())
        .toList();
  }

  // A single-layer source with the profile's record at level 6 on the profile's base.
  private static SourceBuilder dolbyVisionSource(DolbyVisionProfile profile) {
    var range =
        new VideoDynamicRange(
            profile.compatibleBase(),
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(profile.profileNumber()))
                        .level(OptionalInt.of(SOURCE_LEVEL))
                        .baseLayerCompatibilityId(OptionalInt.of(compatibilityIdOf(profile)))
                        .baseLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT));
    var codec =
        switch (profile.codecFamily()) {
          case AV1 -> Codec.AV1_MAIN;
          default -> Codec.HEVC_MAIN_10;
        };
    return sourceBuilder().codec(codec).dynamicRange(range);
  }

  // A single-track profile 7 source: its record names profile 7 with an enhancement layer at level
  // 6, and its RPUs ride in the one video stream, each at the end of its own picture's sample.
  private static SourceBuilder singleTrackProfile7Source() {
    var range =
        new VideoDynamicRange(
            CompatibleBase.PQ,
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(7))
                        .level(OptionalInt.of(SOURCE_LEVEL))
                        .baseLayerCompatibilityId(OptionalInt.of(6))
                        .baseLayer(true)
                        .enhancementLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT,
                Optional.of(
                    DolbyVisionRpuHeader.builder()
                        .vdrRpuProfile(1)
                        .enhancementLayerSpatialResamplingFilter(true)
                        .vdrBitDepth(12)
                        .build()),
                DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
    return sourceBuilder().dynamicRange(range);
  }

  private static int compatibilityIdOf(DolbyVisionProfile profile) {
    return switch (profile.compatibleBase()) {
      case HLG -> 4;
      case NONE -> 0;
      default -> 1;
    };
  }

  /** A 10-bit video codec that a source can carry, with its codec string. */
  private enum Codec {
    HEVC_MAIN_10("hevc", "hvc1.2.4.L150.90", VideoCodecProfile.HEVC_MAIN_10),
    AV1_MAIN("av1", "av01.0.08M.10", VideoCodecProfile.AV1_MAIN);

    private final String probedName;
    private final String codecString;
    private final VideoCodecProfile profile;

    Codec(String probedName, String codecString, VideoCodecProfile profile) {
      this.probedName = probedName;
      this.codecString = codecString;
      this.profile = profile;
    }
  }

  /**
   * The default 1080p source, whose keyframes open every 6 s interval, as 10-bit video in this
   * dynamic range, beside stereo AAC. It is HEVC Main 10 unless it names its codec, and runs at 24
   * fps unless its frame rate is unknown.
   */
  @Builder(builderMethodName = "sourceBuilder", builderClassName = "SourceBuilder")
  private static MediaProbe source(
      Codec codec, @NonNull VideoDynamicRange dynamicRange, boolean frameRateUnknown) {
    var video = Optional.ofNullable(codec).orElse(Codec.HEVC_MAIN_10);
    var framerate = OptionalDouble.of(24);
    var frameRateRule = VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1));
    if (frameRateUnknown) {
      framerate = OptionalDouble.empty();
      frameRateRule = VideoPlaybackFactsFixture.refusedRule();
    }

    return defaultProbeBuilder()
        .audioChannels(OptionalInt.of(2))
        .containerFormat(Optional.of("matroska,webm"))
        .framerate(framerate)
        .videoCodec(video.probedName)
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(frameRateRule)
                    .codecFamily(Optional.of(ProbedCodecFamily.fromProbedName(video.probedName)))
                    .dynamicRange(dynamicRange)
                    .bitDepth(OptionalInt.of(10))
                    .codecString(Optional.of(video.codecString))
                    .videoProperties(
                        Optional.of(
                            VideoStreamProperties.builder()
                                .codecProfile(Optional.of(video.profile))
                                .codecLevel(OptionalInt.of(40))
                                .codecTier(Optional.of(VideoCodecTier.MAIN))
                                .firstFramesRead(true)
                                .build()))
                    .build()))
        .build();
  }
}
