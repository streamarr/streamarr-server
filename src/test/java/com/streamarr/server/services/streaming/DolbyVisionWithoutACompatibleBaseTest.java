package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.declarationBuilderFor;
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
import com.streamarr.server.domain.streaming.DecisionReason;
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
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoRange;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Playback of Dolby Vision profiles 5 and 10.0, which have no compatible base, through the real
 * transcode decision and playlist (ADR 0013). A client that declares the profile at or above the
 * copy's level receives the copy under its Dolby Vision sample entry, dvh1 for profile 5 and dav1
 * for 10.0, when a connected worker writes that entry. Every other client receives an encode.
 */
@Tag("UnitTest")
@DisplayName("Dolby Vision Without A Compatible Base Tests")
class DolbyVisionWithoutACompatibleBaseTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder()
          .maxConcurrentTranscodes(3)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .build();
  private static final int SOURCE_LEVEL = 6;
  private static final Set<String> ENCODED_CODEC_FAMILIES = Set.of("h264", "av1");
  private static final Codec HEVC_MAIN_10 =
      new Codec("hevc", "hvc1.2.4.L150.90", VideoCodecProfile.HEVC_MAIN_10);
  private static final Codec AV1_MAIN =
      new Codec("av1", "av01.0.08M.10", VideoCodecProfile.AV1_MAIN);

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
    workers.setVideoCopySupportByWorker(
        List.of(workerWriting(VideoSampleEntry.DVH1, VideoSampleEntry.DAV1)));
    playback =
        PlaybackSessionService.builder()
            .streamingService(streaming())
            .authorityGate(new FakePlaybackAuthorityGate())
            .executor(Runnable::run)
            .maximumFormatAttempts(4)
            .build();
  }

  // ADR 0013: the copy keeps its RPUs under the Dolby Vision sample entry, with the record that
  // the playlist describes, and the playlist names that entry in CODECS and the PQ range without
  // SUPPLEMENTAL-CODECS. A copy plays Original, so Original stays selected.
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("copiesUnderADolbyVisionSampleEntry")
  @DisplayName(
      "Should copy under the Dolby Vision sample entry when the client declares the profile and a"
          + " worker writes the entry")
  void shouldCopyUnderTheDolbyVisionSampleEntryWhenTheClientDeclaresTheProfileAndAWorkerWritesIt(
      DolbyVisionProfile profile, VideoQuality quality, String codecs) {
    probes.setDefaultProbe(baselessSource(profile));

    var copy = accepted(playback.start(start(quality, baselessClient())));

    assertThat(copy.playbackChoice()).isEqualTo(quality);
    assertThat(copy.formatChange()).isEmpty();
    assertThat(workers.getStartedRequests().getLast().transcodeDecision().plannedVideoOutput())
        .isInstanceOfSatisfying(
            PlannedVideoOutput.StreamCopy.class,
            output -> {
              assertThat(output.bitstreamRewrites())
                  .containsExactly(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
              assertThat(output.dolbyVisionOutput())
                  .contains(new DolbyVisionOutput(profile, SOURCE_LEVEL));
              assertThat(output.dynamicRange().compatibleBase()).isEqualTo(CompatibleBase.NONE);
            });
    assertThat(sessionOf(copy).getVideoRange()).isEqualTo(VideoRange.PQ);
    assertThat(multivariantPlaylist(copy))
        .contains("CODECS=\"" + codecs + ",mp4a.40.2\"")
        .contains(",VIDEO-RANGE=PQ")
        .doesNotContain("SUPPLEMENTAL-CODECS");
  }

  static Stream<Arguments> copiesUnderADolbyVisionSampleEntry() {
    return Stream.of(
        Arguments.of(DolbyVisionProfile.PROFILE_5, VideoQuality.AUTO, "dvh1.05.06"),
        Arguments.of(DolbyVisionProfile.PROFILE_5, VideoQuality.ORIGINAL, "dvh1.05.06"),
        Arguments.of(DolbyVisionProfile.PROFILE_10_0, VideoQuality.AUTO, "dav1.10.06"));
  }

  // ADR 0013: a picture with no compatible base shows wrong colours without its Dolby Vision
  // sample entry and record, so without a worker that writes the entry the server encodes the
  // stream. The pinned worker writes dvh1 but not dav1, so profile 10.0 encodes beside it.
  @ParameterizedTest(name = "{0} beside {1}")
  @MethodSource("workersWithoutTheSampleEntry")
  @DisplayName(
      "Should encode to SDR and name the missing worker when no connected worker writes the Dolby"
          + " Vision sample entry that the copy needs")
  void shouldEncodeToSdrAndNameTheMissingWorkerWhenNoConnectedWorkerWritesTheSampleEntry(
      DolbyVisionProfile profile, WorkerVideoCopySupport worker) {
    workers.setVideoCopySupportByWorker(List.of(worker));
    probes.setDefaultProbe(baselessSource(profile));

    var encode = accepted(playback.start(start(VideoQuality.AUTO, baselessClient())));

    assertThatAnSdrEncodePlays(encode);
    assertThat(sessionOf(encode).getTranscodeDecision().reasons())
        .containsExactly(DecisionReason.NO_WORKER_DELIVERS_COPY);
  }

  static Stream<Arguments> workersWithoutTheSampleEntry() {
    return Stream.of(
        Arguments.of(
            DolbyVisionProfile.PROFILE_5,
            Named.of("a worker that writes no Dolby Vision sample entry", workerWriting())),
        Arguments.of(
            DolbyVisionProfile.PROFILE_5,
            Named.of("a worker that writes only dav1", workerWriting(VideoSampleEntry.DAV1))),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_0,
            Named.of("a worker that writes only dvh1", workerWriting(VideoSampleEntry.DVH1))));
  }

  // ADR 0013: every client that does not declare profile 5 receives an encode, in every playback
  // choice. Original cannot copy the source, so its encode plays in Auto (ADR 0052).
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("profile5ToClientsThatDoNotDeclareIt")
  @DisplayName(
      "Should encode profile 5 in every playback choice when the client does not declare profile 5")
  void shouldEncodeProfile5InEveryPlaybackChoiceWhenTheClientDoesNotDeclareProfile5(
      ClientCapabilityDeclaration client,
      VideoQuality quality,
      VideoQuality expectedChoice,
      Optional<PlaybackFormatChange> expectedChange) {
    probes.setDefaultProbe(baselessSource(DolbyVisionProfile.PROFILE_5));

    var encode = accepted(playback.start(start(quality, client)));

    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(encode.formatChange()).isEqualTo(expectedChange);
    assertThatAnSdrEncodePlays(encode);
  }

  static Stream<Arguments> profile5ToClientsThatDoNotDeclareIt() {
    var startedWithEncode = Optional.of(PlaybackFormatChange.STARTED_WITH_ENCODE);
    return Stream.of(
            Named.of(
                "a browser that declares SDR HEVC and H.264",
                declarationBuilderFor(ProbedCodecFamily.HEVC, ProbedCodecFamily.H264).build()),
            Named.of(
                "a client that declares every base and profile 8.1 at level 13", profile81Client()))
        .flatMap(
            client ->
                Stream.of(
                    Arguments.of(client, VideoQuality.AUTO, VideoQuality.AUTO, Optional.empty()),
                    Arguments.of(
                        client,
                        VideoQuality.FULL_HD_1080P,
                        VideoQuality.FULL_HD_1080P,
                        Optional.empty()),
                    Arguments.of(
                        client, VideoQuality.ORIGINAL, VideoQuality.AUTO, startedWithEncode)));
  }

  // #401 and ADR 0052: profiles 5 and 10.0 have no compatible base that a stripped copy could
  // play, so with the default budget of four an encode replaces a rejected Dolby Vision copy as the
  // second format attempt. Original's encode plays in Auto, and a fixed quality keeps its choice.
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("rejectedDolbyVisionCopies")
  @DisplayName(
      "Should start an encode as the second format attempt when the client rejects the Dolby"
          + " Vision copy")
  void shouldStartAnEncodeAsTheSecondFormatAttemptWhenTheClientRejectsTheDolbyVisionCopy(
      DolbyVisionProfile profile,
      VideoQuality quality,
      VideoQuality expectedChoice,
      Optional<PlaybackFormatChange> expectedChange) {
    probes.setDefaultProbe(baselessSource(profile));
    var command = start(quality, baselessClient());
    var copy = accepted(playback.start(command));

    var encode = rejectCurrentFormatAttempt(copy, command);

    assertThat(sessionOf(copy).getTranscodeDecision().videoDolbyVisionOutput())
        .contains(new DolbyVisionOutput(profile, SOURCE_LEVEL));
    assertThat(encode.formatAttemptCount()).isEqualTo(2);
    assertThat(encode.playbackChoice()).isEqualTo(expectedChoice);
    assertThat(encode.formatChange()).isEqualTo(expectedChange);
    assertThatAnSdrEncodePlays(encode);
  }

  static Stream<Arguments> rejectedDolbyVisionCopies() {
    var copyFailed = Optional.of(PlaybackFormatChange.COPY_FAILED);
    return Stream.of(
        Arguments.of(
            DolbyVisionProfile.PROFILE_5, VideoQuality.AUTO, VideoQuality.AUTO, Optional.empty()),
        Arguments.of(
            DolbyVisionProfile.PROFILE_5, VideoQuality.ORIGINAL, VideoQuality.AUTO, copyFailed),
        Arguments.of(
            DolbyVisionProfile.PROFILE_5,
            VideoQuality.FULL_HD_1080P,
            VideoQuality.FULL_HD_1080P,
            copyFailed),
        Arguments.of(
            DolbyVisionProfile.PROFILE_10_0, VideoQuality.ORIGINAL, VideoQuality.AUTO, copyFailed));
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

  private void assertThatAnSdrEncodePlays(PlaybackSessionSnapshot snapshot) {
    var session = sessionOf(snapshot);
    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isFalse();
    assertThat(session.getVideoRange()).isEqualTo(VideoRange.SDR);
    assertThat(multivariantPlaylist(snapshot))
        .contains("#EXT-X-STREAM-INF:")
        .doesNotContain("VIDEO-RANGE=")
        .doesNotContain("dvh1")
        .doesNotContain("dav1");
  }

  private static StreamSession sessionOf(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("Expected a ready format attempt: " + snapshot.currentFormatAttempt());
  }

  private static String multivariantPlaylist(PlaybackSessionSnapshot snapshot) {
    return new HlsPlaylistService(PROPERTIES)
        .generateMultivariantPlaylist(sessionOf(snapshot), "t");
  }

  private StartPlaybackSessionCommand start(
      VideoQuality quality, ClientCapabilityDeclaration client) {
    var file =
        mediaFiles.save(
            MediaFile.builder()
                .filepathUri("file:///media/movies/dolby-vision.mp4")
                .filename("dolby-vision.mp4")
                .status(MediaFileStatus.MATCHED)
                .size(1_000_000L)
                .build());
    return StartPlaybackSessionCommand.builder()
        .mediaFileId(file.getId())
        .identity(identityFor(defaultPlaybackAuthorityBuilder().build()))
        .options(StreamingOptions.builder().quality(quality).build())
        .capabilityDeclaration(client)
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

  // A worker that applies every rewrite and writes the Dolby Vision sample entries.
  private static WorkerVideoCopySupport workerWriting(VideoSampleEntry... sampleEntries) {
    return WorkerVideoCopySupport.builder()
        .plannedOutput(true)
        .bitstreamRewrites(Set.of(BitstreamRewrite.values()))
        .dolbyVisionSampleEntries(Set.of(sampleEntries))
        .build();
  }

  // HEVC and AV1 entries that list profiles 5 and 10.0 up to level 13 without a compatible base,
  // which such a copy does not need (ADR 0049), beside H.264 SDR and stereo AAC.
  private static ClientCapabilityDeclaration baselessClient() {
    return defaultDeclarationBuilder()
        .video(
            List.of(
                hevcEntryBuilder()
                    .compatibleBases(List.of())
                    .dolbyVisionProfiles(levelThirteen(DolbyVisionProfile.PROFILE_5))
                    .build(),
                av1EntryBuilder()
                    .compatibleBases(List.of())
                    .dolbyVisionProfiles(levelThirteen(DolbyVisionProfile.PROFILE_10_0))
                    .build(),
                h264EntryBuilder().build()))
        .build();
  }

  private static ClientCapabilityDeclaration profile81Client() {
    return defaultDeclarationBuilder()
        .video(
            List.of(
                hevcEntryBuilder()
                    .compatibleBases(
                        List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
                    .dolbyVisionProfiles(levelThirteen(DolbyVisionProfile.PROFILE_8_1))
                    .hdr10Plus(true)
                    .build(),
                h264EntryBuilder().build()))
        .build();
  }

  private static List<DolbyVisionProfileCapability> levelThirteen(DolbyVisionProfile profile) {
    return List.of(DolbyVisionProfileCapability.builder().profile(profile).maxLevel(13).build());
  }

  // The default 1080p source at 24 fps, whose keyframes open every 6 s interval, as 10-bit video
  // with the profile's record at level 6 and no compatible base, beside stereo AAC.
  private static MediaProbe baselessSource(DolbyVisionProfile profile) {
    var range =
        new VideoDynamicRange(
            CompatibleBase.NONE,
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(profile.profileNumber()))
                        .level(OptionalInt.of(SOURCE_LEVEL))
                        .baseLayerCompatibilityId(OptionalInt.of(0))
                        .baseLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT));
    var codec = HEVC_MAIN_10;
    if (profile.codecFamily() == ProbedCodecFamily.AV1) {
      codec = AV1_MAIN;
    }

    return defaultProbeBuilder()
        .videoCodec(codec.probedName())
        .videoPlaybackFacts(
            Optional.of(
                VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                    .streamIndex(0)
                    .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                    .codecFamily(Optional.of(profile.codecFamily()))
                    .dynamicRange(range)
                    .bitDepth(OptionalInt.of(10))
                    .codecString(Optional.of(codec.codecString()))
                    .videoProperties(
                        Optional.of(
                            VideoStreamProperties.builder()
                                .codecProfile(Optional.of(codec.profile()))
                                .codecLevel(OptionalInt.of(40))
                                .codecTier(Optional.of(VideoCodecTier.MAIN))
                                .firstFramesRead(true)
                                .build()))
                    .build()))
        .build();
  }

  /** A 10-bit video codec that a source can carry, with the codec string that the probe stored. */
  private record Codec(String probedName, String codecString, VideoCodecProfile profile) {}
}
