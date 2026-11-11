package com.streamarr.server.services.streaming;

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
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.EncoderBackend;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoEncodeFeature;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoSampleEntry;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The encoded lower variants beside an Auto copy keep the copy's range and bit depth, through the
 * real transcode decision, ladder and playlist (ADR 0050). A bandwidth dip must not move a
 * television between HDR and SDR, so a copy whose range no verified encoder writes, or whose range
 * the client plays in no codec that a worker encodes, lists no lower variants.
 */
@Tag("UnitTest")
@DisplayName("Lower Variants In The Copy's Range Tests")
class LowerVariantsInTheCopysRangeTest {

  private static final StreamingProperties PROPERTIES =
      StreamingProperties.builder()
          .maxConcurrentTranscodes(3)
          .targetSegmentDuration(Duration.ofSeconds(6))
          .sessionTimeout(Duration.ofSeconds(60))
          .build();
  private static final int DOLBY_VISION_LEVEL = 6;
  private static final Set<String> ENCODED_CODEC_FAMILIES = Set.of("h264", "hevc", "av1");
  private static final Set<VideoEncodeFeature> EVERY_ENCODE_FEATURE =
      Set.of(VideoEncodeFeature.values());
  private static final List<CompatibleBase> EVERY_RANGE =
      List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG);

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
        List.of(
            WorkerVideoCopySupport.builder()
                .plannedOutput(true)
                .bitstreamRewrites(Set.of(BitstreamRewrite.values()))
                .dolbyVisionSampleEntries(Set.of(VideoSampleEntry.DVH1))
                .build()));
    playback =
        PlaybackSessionService.builder()
            .eventPublisher(_ -> {})
            .streamingService(streaming())
            .authorityGate(new FakePlaybackAuthorityGate())
            .executor(Runnable::run)
            .maximumFormatAttempts(4)
            .build();
  }

  // The fallback is no lower variants, never SDR ones: the copy plays alone, as it does in a fixed
  // quality.
  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesWhoseRangeNoVerifiedEncoderWrites")
  @DisplayName(
      "Should list the copy alone when no verified encoder lists the encode features that the"
          + " copy's range and bit depth need")
  void shouldListTheCopyAloneWhenNoVerifiedEncoderListsTheEncodeFeaturesThatItsRangeNeeds(
      CopyAlone copyAlone) {
    workers.setTargetEncoders(ExecutionTargetId.LOCAL, copyAlone.encoders());
    probes.setDefaultProbe(copyAlone.source());

    var copy = accepted(playback.start(startAuto(clientThatPlaysEveryRange())));

    assertThatTheCopyPlaysAlone(copy);
  }

  static Stream<CopyAlone> copiesWhoseRangeNoVerifiedEncoderWrites() {
    return Stream.of(
        copyAlone("HDR10 beside an AV1 encoder without PQ")
            .source(hevc(CompatibleBase.PQ))
            .encoder(svtAv1(without(VideoEncodeFeature.PQ)))
            .build(),
        copyAlone("HLG beside an AV1 encoder without HLG")
            .source(hevc(CompatibleBase.HLG))
            .encoder(svtAv1(without(VideoEncodeFeature.HLG)))
            .build(),
        copyAlone("10-bit SDR beside an AV1 encoder without 10 bits")
            .source(hevc(CompatibleBase.SDR))
            .encoder(svtAv1(without(VideoEncodeFeature.TEN_BIT)))
            .build(),
        copyAlone("Dolby Vision 8.1 beside an AV1 encoder without PQ")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_8_1))
            .encoder(svtAv1(without(VideoEncodeFeature.PQ)))
            .build(),
        copyAlone("Dolby Vision 5 beside an AV1 encoder that cannot reshape Dolby Vision into PQ")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_5))
            .encoder(svtAv1(without(VideoEncodeFeature.DOLBY_VISION_TO_PQ)))
            .build(),
        copyAlone("HDR10 beside encoders that list no encode feature")
            .source(hevc(CompatibleBase.PQ))
            .encoder(svtAv1(Set.of()))
            .encoder(encoder("h264", "libx264"))
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("clientsThatPlayNoEncodeInTheCopysRange")
  @DisplayName(
      "Should list the copy alone when no capability entry plays an encode in the copy's range and"
          + " bit depth")
  void shouldListTheCopyAloneWhenNoCapabilityEntryPlaysAnEncodeInTheCopysRangeAndBitDepth(
      CopyAlone copyAlone) {
    workers.setTargetEncoders(
        ExecutionTargetId.LOCAL, List.of(svtAv1(EVERY_ENCODE_FEATURE), encoder("h264", "libx264")));
    probes.setDefaultProbe(copyAlone.source());

    var copy = accepted(playback.start(startAuto(copyAlone.client())));

    assertThatTheCopyPlaysAlone(copy);
  }

  static Stream<CopyAlone> clientsThatPlayNoEncodeInTheCopysRange() {
    var hdrHevc = hevcEntryBuilder().compatibleBases(EVERY_RANGE).build();
    return Stream.of(
        copyAlone("HDR10 on a client whose AV1 entry declares SDR alone")
            .source(hevc(CompatibleBase.PQ))
            .client(clientWith(hdrHevc, av1EntryBuilder().build()))
            .build(),
        copyAlone("HDR10 on a client whose AV1 entry declares PQ at 8 bits")
            .source(hevc(CompatibleBase.PQ))
            .client(
                clientWith(
                    hdrHevc, av1EntryBuilder().compatibleBases(EVERY_RANGE).maxBitDepth(8).build()))
            .build(),
        copyAlone("10-bit SDR on a client whose AV1 entry declares 8 bits")
            .source(hevc(CompatibleBase.SDR))
            .client(clientWith(hdrHevc, av1EntryBuilder().maxBitDepth(8).build()))
            .build(),
        copyAlone("Dolby Vision 5 on a client that declares profile 5 but no PQ")
            .source(dolbyVisionSource(DolbyVisionProfile.PROFILE_5))
            .client(
                clientWith(
                    hevcEntryBuilder()
                        .dolbyVisionProfiles(profiles(DolbyVisionProfile.PROFILE_5))
                        .build(),
                    av1EntryBuilder().build()))
            .build());
  }

  private void assertThatTheCopyPlaysAlone(PlaybackSessionSnapshot copy) {
    var session = sessionOf(copy);
    assertThat(session.getTranscodeDecision().transcodeMode().copiesVideo()).isTrue();
    assertThat(session.getVariants()).isEmpty();
    assertThat(streamInfEntries(copy)).hasSize(1);
  }

  /** A copy that lists no lower variants beside these encoders and on this client. */
  @Builder(builderMethodName = "copyAloneBuilder")
  private record CopyAlone(
      @NonNull String name,
      @NonNull MediaProbe source,
      @Singular List<WorkerVerifiedEncoder> encoders,
      @NonNull ClientCapabilityDeclaration client) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static CopyAlone.CopyAloneBuilder copyAlone(String name) {
    return CopyAlone.copyAloneBuilder().name(name).client(clientThatPlaysEveryRange());
  }

  private List<String> streamInfEntries(PlaybackSessionSnapshot snapshot) {
    return new HlsPlaylistService(PROPERTIES, UploadBudgetFixture.OFF)
        .generateMultivariantPlaylist(sessionOf(snapshot), "t")
        .lines()
        .filter(line -> line.startsWith("#EXT-X-STREAM-INF:"))
        .toList();
  }

  private static StreamSession sessionOf(PlaybackSessionSnapshot snapshot) {
    if (snapshot.currentFormatAttempt() instanceof PlaybackFormatAttempt.Ready(var session)) {
      return session;
    }

    throw new AssertionError("Expected a ready format attempt: " + snapshot.currentFormatAttempt());
  }

  private StartPlaybackSessionCommand startAuto(ClientCapabilityDeclaration client) {
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
        .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build())
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

  // An HEVC entry that declares every range, HDR10+ and Dolby Vision profiles 5, 8.1 and 8.4, and
  // an AV1 entry that declares every range at 10 bits, beside H.264 SDR and stereo AAC.
  private static ClientCapabilityDeclaration clientThatPlaysEveryRange() {
    return clientWith(
        hevcEntryBuilder()
            .compatibleBases(EVERY_RANGE)
            .hdr10Plus(true)
            .dolbyVisionProfiles(
                profiles(
                    DolbyVisionProfile.PROFILE_5,
                    DolbyVisionProfile.PROFILE_8_1,
                    DolbyVisionProfile.PROFILE_8_4))
            .build(),
        av1EntryBuilder().compatibleBases(EVERY_RANGE).build());
  }

  // The entries beside H.264 SDR and stereo AAC.
  private static ClientCapabilityDeclaration clientWith(VideoCapabilityEntry... entries) {
    return defaultDeclarationBuilder()
        .video(
            Stream.concat(Arrays.stream(entries), Stream.of(h264EntryBuilder().build()))
                .distinct()
                .toList())
        .build();
  }

  private static List<DolbyVisionProfileCapability> profiles(DolbyVisionProfile... profiles) {
    return Stream.of(profiles)
        .map(
            profile -> DolbyVisionProfileCapability.builder().profile(profile).maxLevel(13).build())
        .toList();
  }

  private static Set<VideoEncodeFeature> without(VideoEncodeFeature feature) {
    var features = EnumSet.allOf(VideoEncodeFeature.class);
    features.remove(feature);
    return features;
  }

  private static WorkerVerifiedEncoder svtAv1(Set<VideoEncodeFeature> features) {
    return encoder("av1", "libsvtav1", features);
  }

  // A software encoder that lists no encode feature, so it writes 8-bit SDR alone.
  private static WorkerVerifiedEncoder encoder(String codecFamily, String encoder) {
    return encoder(codecFamily, encoder, Set.of());
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

  // The default source in the compatible base, with neither HDR10+ nor Dolby Vision.
  private static MediaProbe hevc(CompatibleBase base) {
    return hevcSource(VideoPlaybackFactsFixture.rangeWithoutEnhancement(base));
  }

  private static MediaProbe hevcSource(VideoDynamicRange range) {
    return source(hevcFacts().dynamicRange(range).build());
  }

  // A single-layer source with the profile's record at level 6 on the profile's base.
  private static MediaProbe dolbyVisionSource(DolbyVisionProfile profile) {
    var compatibilityId =
        switch (profile.compatibleBase()) {
          case HLG -> 4;
          case NONE -> 0;
          default -> 1;
        };
    return hevcSource(
        new VideoDynamicRange(
            profile.compatibleBase(),
            Presence.ABSENT,
            new DolbyVision.Present(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(profile.profileNumber()))
                        .level(OptionalInt.of(DOLBY_VISION_LEVEL))
                        .baseLayerCompatibilityId(OptionalInt.of(compatibilityId))
                        .baseLayer(true)
                        .rpu(true)
                        .build()),
                Presence.PRESENT)));
  }

  // 10-bit HEVC Main 10 at 24 fps.
  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder hevcFacts() {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .streamIndex(0)
        .frameRateRule(VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
        .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
        .bitDepth(OptionalInt.of(10))
        .codecString(Optional.of("hvc1.2.4.L150.90"))
        .videoProperties(
            Optional.of(
                VideoStreamProperties.builder()
                    .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                    .codecLevel(OptionalInt.of(40))
                    .codecTier(Optional.of(VideoCodecTier.MAIN))
                    .firstFramesRead(true)
                    .build()));
  }

  // The default 1080p source, whose keyframes open every 6 s interval, beside stereo AAC.
  private static MediaProbe source(VideoPlaybackFacts facts) {
    return defaultProbeBuilder()
        .audioChannels(OptionalInt.of(2))
        .containerFormat(Optional.of("matroska,webm"))
        .videoCodec("hevc")
        .videoPlaybackFacts(Optional.of(facts))
        .build();
  }
}
