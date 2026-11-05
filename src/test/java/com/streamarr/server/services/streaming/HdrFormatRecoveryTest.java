package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
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
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PlannedVideoOutput;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.fakes.FakeMediaFileContainerInfoRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakePlaybackAuthorityGate;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Format recovery from a rejected HDR copy through the real transcode decision and playlist (ADR
 * 0052): a Dolby Vision copy on a PQ or HLG base falls back to the copy with Dolby Vision stripped,
 * and an HDR copy falls back to an SDR encode until the server encodes HDR (#397).
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

  // ADR 0052 and #401: every encode is SDR until #397, so the encode that replaces a rejected HDR
  // copy says SDR in its decision, its playlist entry and the stream session's range, which the
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
    return new HlsPlaylistService(PROPERTIES)
        .generateMultivariantPlaylist(sessionOf(snapshot), "t");
  }

  private StartPlaybackSessionCommand start(VideoQuality quality) {
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
        .options(StreamingOptions.builder().quality(quality).build())
        .capabilityDeclaration(dolbyVisionClient())
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
    var bases = List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);
    return defaultDeclarationBuilder()
        .video(
            List.of(
                hevcEntryBuilder()
                    .compatibleBases(bases)
                    .dolbyVisionProfiles(
                        profiles(DolbyVisionProfile.PROFILE_8_1, DolbyVisionProfile.PROFILE_8_4))
                    .build(),
                av1EntryBuilder()
                    .compatibleBases(bases)
                    .dolbyVisionProfiles(
                        profiles(DolbyVisionProfile.PROFILE_10_1, DolbyVisionProfile.PROFILE_10_4))
                    .build(),
                h264EntryBuilder().build()))
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

  private static int compatibilityIdOf(DolbyVisionProfile profile) {
    return profile.compatibleBase() == CompatibleBase.HLG ? 4 : 1;
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
