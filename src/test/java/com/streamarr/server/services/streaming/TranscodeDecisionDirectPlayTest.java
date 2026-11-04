package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DolbyVisionRpuPlacement;
import com.streamarr.server.domain.streaming.FieldOrder;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.Presence;
import com.streamarr.server.domain.streaming.ProbedCodecFamily;
import com.streamarr.server.domain.streaming.SourceContainer;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.VideoCapabilityEntry;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecTier;
import com.streamarr.server.domain.streaming.VideoDynamicRange;
import com.streamarr.server.domain.streaming.VideoPlaybackFacts;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.domain.streaming.VideoStreamProperties;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The direct-play decision (ADR 0045, streamarr/streamarr-server#59): the first format attempt of
 * an Original playback session sends the source file itself when the declaration covers it.
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision Direct Play Tests")
class TranscodeDecisionDirectPlayTest {

  private static final String MATROSKA_DEMUXER = "matroska,webm";

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @Test
  @DisplayName("Should direct-play the source in Original when the declaration covers the file")
  void shouldDirectPlayTheSourceInOriginalWhenTheDeclarationCoversTheFile() {
    assertThat(service.decideDirectPlay(request().build())).contains(SourceContainer.MATROSKA);
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should play over HLS when the playback choice is not Original")
  void shouldPlayOverHlsWhenThePlaybackChoiceIsNotOriginal(VideoQuality quality) {
    var request = request().viewerLimits(ViewerLimits.builder().quality(quality).build()).build();

    assertThat(service.decideDirectPlay(request)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoFormatConstraint.class,
      names = {"DIRECT_PLAY_OR_COPY_OR_ENCODE"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should play over HLS when the format attempt may not direct-play")
  void shouldPlayOverHlsWhenTheFormatAttemptMayNotDirectPlay(VideoFormatConstraint constraint) {
    assertThat(service.decideDirectPlay(request().videoFormatConstraint(constraint).build()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should play over HLS when the declaration does not cover the file's container")
  void shouldPlayOverHlsWhenTheDeclarationDoesNotCoverTheFilesContainer() {
    var request = request().source(h264Source().containerFormat(Optional.of("mpegts")).build());

    assertThat(service.decideDirectPlay(request.build())).isEmpty();
  }

  // Probe version 7 adds the major brand that tells MP4 from MOV (streamarr/streamarr-server#59).
  @ParameterizedTest
  @ValueSource(strings = {"mov,mp4,m4a,3gp,3g2,mj2", "wav"})
  @DisplayName("Should play over HLS when the probe's demuxer names no single source container")
  void shouldPlayOverHlsWhenTheProbesDemuxerNamesNoSingleSourceContainer(String demuxer) {
    var everyContainer = declarationListing(List.of(SourceContainer.values()));
    var request =
        request()
            .capabilityDeclaration(everyContainer)
            .source(h264Source().containerFormat(Optional.of(demuxer)).build());

    assertThat(service.decideDirectPlay(request.build())).isEmpty();
  }

  @Test
  @DisplayName("Should play over HLS when the probe reports no container")
  void shouldPlayOverHlsWhenTheProbeReportsNoContainer() {
    var request = request().source(h264Source().containerFormat(Optional.empty()).build());

    assertThat(service.decideDirectPlay(request.build())).isEmpty();
  }

  @Test
  @DisplayName("Should play over HLS when the video is interlaced")
  void shouldPlayOverHlsWhenTheVideoIsInterlaced() {
    var interlaced =
        h264Facts()
            .frameRateRule(
                VideoPlaybackFactsFixture.interlacedRateRule(new Fraction(25, 1), FieldOrder.TT))
            .build();
    var request =
        request().source(h264Source().videoPlaybackFacts(Optional.of(interlaced)).build());

    assertThat(service.decideDirectPlay(request.build())).isEmpty();
  }

  @Test
  @DisplayName("Should play over HLS when a property issue rules out a copy of the video")
  void shouldPlayOverHlsWhenAPropertyIssueRulesOutACopyOfTheVideo() {
    var profile81Entry =
        hevcEntryBuilder()
            .compatibleBases(List.of(CompatibleBase.PQ))
            .dolbyVisionProfiles(
                List.of(
                    DolbyVisionProfileCapability.builder()
                        .profile(DolbyVisionProfile.PROFILE_8_1)
                        .maxLevel(13)
                        .build()))
            .directPlayContainers(List.of(SourceContainer.MATROSKA))
            .build();
    var declaration = declarationWith(profile81Entry);
    var placedAfter = profile81(DolbyVisionRpuPlacement.AFTER_OWN_PICTURE);
    var startingSample = profile81(DolbyVisionRpuPlacement.STARTS_SAMPLE);

    assertThat(
            service.decideDirectPlay(
                request()
                    .capabilityDeclaration(declaration)
                    .source(hevcSource(placedAfter))
                    .build()))
        .contains(SourceContainer.MATROSKA);
    assertThat(
            service.decideDirectPlay(
                request()
                    .capabilityDeclaration(declaration)
                    .source(hevcSource(startingSample))
                    .build()))
        .isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("forcedImageSubtitles")
  @DisplayName(
      "Should play over HLS only when a forced image subtitle matches the first audio language")
  void shouldPlayOverHlsOnlyWhenAForcedImageSubtitleMatchesTheFirstAudioLanguage(
      String scenario, String subtitleLanguage, boolean directPlays) {
    var forced =
        StreamInfo.builder()
            .codecType("subtitle")
            .codec(Optional.of("hdmv_pgs_subtitle"))
            .language(Optional.of(subtitleLanguage))
            .isForced(true)
            .build();
    var source = h264Source().streams(List.of(englishAac(), forced)).build();

    assertThat(service.decideDirectPlay(request().source(source).build()).isPresent())
        .isEqualTo(directPlays);
  }

  static Stream<Arguments> forcedImageSubtitles() {
    return Stream.of(
        Arguments.of("forced track in the audio language", "eng", false),
        Arguments.of("forced track in another language", "fra", true));
  }

  // The keyframe grid, the interim HDR rule and the codec string bind only HLS copies.
  @Test
  @DisplayName("Should direct-play an HDR file whose keyframes are unknown")
  void shouldDirectPlayAnHdrFileWhoseKeyframesAreUnknown() {
    var pqEntry =
        hevcEntryBuilder()
            .compatibleBases(List.of(CompatibleBase.PQ))
            .directPlayContainers(List.of(SourceContainer.MATROSKA))
            .build();
    var source =
        hevcSourceBuilder(VideoPlaybackFactsFixture.rangeWithoutEnhancement(CompatibleBase.PQ))
            .keyframes(new KeyframeKnowledge.NotReported())
            .build();

    assertThat(
            service.decideDirectPlay(
                request().capabilityDeclaration(declarationWith(pqEntry)).source(source).build()))
        .contains(SourceContainer.MATROSKA);
  }

  // A copy in Original ignores the viewer's limits (ADR 0050), and so does direct play.
  @Test
  @DisplayName("Should direct-play in Original when the viewer's limits are below the source")
  void shouldDirectPlayInOriginalWhenTheViewersLimitsAreBelowTheSource() {
    var limits =
        ViewerLimits.builder()
            .quality(VideoQuality.ORIGINAL)
            .maxWidth(OptionalInt.of(640))
            .maxHeight(OptionalInt.of(360))
            .maxBitrate(OptionalLong.of(500_000))
            .build();

    assertThat(service.decideDirectPlay(request().viewerLimits(limits).build()))
        .contains(SourceContainer.MATROSKA);
  }

  @Test
  @DisplayName("Should play over HLS when the declaration does not cover the audio")
  void shouldPlayOverHlsWhenTheDeclarationDoesNotCoverTheAudio() {
    var source =
        h264Source()
            .streams(
                List.of(
                    englishAac(), audioStreamBuilder("ac3").channels(OptionalInt.of(6)).build()))
            .build();

    assertThat(service.decideDirectPlay(request().source(source).build())).isEmpty();
  }

  private static DirectPlayRequest.DirectPlayRequestBuilder request() {
    return DirectPlayRequest.builder()
        .source(h264Source().build())
        .capabilityDeclaration(declarationListing(List.of(SourceContainer.MATROSKA)))
        .videoFormatConstraint(VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE)
        .viewerLimits(ViewerLimits.builder().quality(VideoQuality.ORIGINAL).build());
  }

  private static ClientCapabilityDeclaration declarationListing(List<SourceContainer> containers) {
    return ClientCapabilityDeclaration.builder()
        .video(List.of(h264EntryBuilder().directPlayContainers(containers).build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(containers).build()))
        .build();
  }

  private static ClientCapabilityDeclaration declarationWith(VideoCapabilityEntry video) {
    return ClientCapabilityDeclaration.builder()
        .video(List.of(video))
        .audio(
            List.of(
                aacEntryBuilder().directPlayContainers(List.of(SourceContainer.MATROSKA)).build()))
        .build();
  }

  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder h264Facts() {
    return VideoPlaybackFactsFixture.sdrH264FactsBuilder()
        .streamIndex(0)
        .videoProperties(
            Optional.of(VideoPlaybackFactsFixture.h264HighPropertiesBuilder().build()));
  }

  private static MediaProbe.MediaProbeBuilder h264Source() {
    return StreamSessionFixture.defaultProbeBuilder()
        .containerFormat(Optional.of(MATROSKA_DEMUXER))
        .videoPlaybackFacts(Optional.of(h264Facts().build()))
        .streams(List.of(englishAac()));
  }

  private static MediaProbe hevcSource(VideoDynamicRange range) {
    return hevcSourceBuilder(range).build();
  }

  private static MediaProbe.MediaProbeBuilder hevcSourceBuilder(VideoDynamicRange range) {
    var facts =
        h264Facts()
            .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
            .codecString(Optional.of("hvc1.2.4.L123.B0"))
            .bitDepth(OptionalInt.of(10))
            .dynamicRange(range)
            .videoProperties(
                Optional.of(
                    VideoStreamProperties.builder()
                        .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                        .codecLevel(OptionalInt.of(41))
                        .codecTier(Optional.of(VideoCodecTier.MAIN))
                        .firstFramesRead(true)
                        .build()))
            .build();
    return h264Source().videoCodec("hevc").videoPlaybackFacts(Optional.of(facts));
  }

  private static StreamInfo englishAac() {
    return audioStreamBuilder("aac")
        .channels(OptionalInt.of(2))
        .language(Optional.of("eng"))
        .build();
  }

  // Profile 8.1 at Dolby Vision level 6, with its RPUs placed as given.
  private static VideoDynamicRange profile81(DolbyVisionRpuPlacement placement) {
    return new VideoDynamicRange(
        CompatibleBase.PQ,
        Presence.ABSENT,
        new DolbyVision.Present(
            Optional.of(
                DolbyVisionConfiguration.builder()
                    .profile(OptionalInt.of(8))
                    .level(OptionalInt.of(6))
                    .baseLayerCompatibilityId(OptionalInt.of(1))
                    .baseLayer(true)
                    .rpu(true)
                    .build()),
            Presence.PRESENT,
            Optional.empty(),
            placement));
  }
}
