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
import com.streamarr.server.domain.streaming.VideoSampleEntry;
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

/**
 * The direct-play decision (ADR 0045, streamarr/streamarr-server#59): the first format attempt of
 * an Original playback session sends the source file itself when the declaration covers it.
 */
@Tag("UnitTest")
@DisplayName("Transcode Decision Direct Play Tests")
class TranscodeDecisionDirectPlayTest {

  private static final String MATROSKA_DEMUXER = "matroska,webm";
  private static final String MOV_DEMUXER = "mov,mp4,m4a,3gp,3g2,mj2";

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @Test
  @DisplayName("Should direct-play the source in Original when the declaration covers the file")
  void shouldDirectPlayTheSourceInOriginalWhenTheDeclarationCoversTheFile() {
    assertThat(decidedContainer(request().build())).contains(SourceContainer.MATROSKA);
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should play over HLS when the playback choice is not Original")
  void shouldPlayOverHlsWhenThePlaybackChoiceIsNotOriginal(VideoQuality quality) {
    var request = request().viewerLimits(ViewerLimits.builder().quality(quality).build()).build();

    assertThat(decidedContainer(request)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoFormatConstraint.class,
      names = {"DIRECT_PLAY_OR_COPY_OR_ENCODE"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should play over HLS when the format attempt may not direct-play")
  void shouldPlayOverHlsWhenTheFormatAttemptMayNotDirectPlay(VideoFormatConstraint constraint) {
    assertThat(decidedContainer(request().videoFormatConstraint(constraint).build())).isEmpty();
  }

  @Test
  @DisplayName("Should play over HLS when the declaration does not cover the file's container")
  void shouldPlayOverHlsWhenTheDeclarationDoesNotCoverTheFilesContainer() {
    var request = request().source(h264Source().containerFormat(Optional.of("mpegts")).build());

    assertThat(decidedContainer(request.build())).isEmpty();
  }

  @Test
  @DisplayName("Should play over HLS when the probe's demuxer names no video container")
  void shouldPlayOverHlsWhenTheProbesDemuxerNamesNoVideoContainer() {
    var everyContainer = declarationListing(List.of(SourceContainer.values()));
    var request =
        request()
            .capabilityDeclaration(everyContainer)
            .source(h264Source().containerFormat(Optional.of("wav")).build());

    assertThat(decidedContainer(request.build())).isEmpty();
  }

  // The major brand tells MP4 from MOV: QuickTime's brand makes MOV, every other brand makes MP4,
  // and a file without an ftyp box counts as MP4 (ADR 0045).
  @ParameterizedTest(name = "major brand {0} in an entry for {1}")
  @MethodSource("majorBrands")
  @DisplayName(
      "Should direct-play an MP4 or MOV file only when its entry lists the brand's container")
  void shouldDirectPlayAnMp4OrMovFileOnlyWhenItsEntryListsTheBrandsContainer(
      Optional<String> majorBrand, SourceContainer listed, Optional<SourceContainer> expected) {
    var request =
        request()
            .capabilityDeclaration(isoDeclarationListing(listed))
            .source(isoSource(majorBrand).build());

    assertThat(decidedContainer(request.build())).isEqualTo(expected);
  }

  static Stream<Arguments> majorBrands() {
    var mp4 = Optional.of(SourceContainer.MP4);
    var mov = Optional.of(SourceContainer.MOV);
    return Stream.of(
        Arguments.of(Optional.of("isom"), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("mp42"), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("M4V "), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("3gp4"), SourceContainer.MP4, mp4),
        Arguments.of(Optional.empty(), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("qt  "), SourceContainer.MOV, mov),
        Arguments.of(Optional.of("qt  "), SourceContainer.MP4, Optional.empty()),
        Arguments.of(Optional.of("isom"), SourceContainer.MOV, Optional.empty()),
        Arguments.of(Optional.empty(), SourceContainer.MOV, Optional.empty()));
  }

  // An MP4 or MOV file is covered only under a sample entry that the entry lists, and a file probed
  // before version 7 has no stored sample entry (ADR 0045).
  @ParameterizedTest(name = "{0}")
  @MethodSource("sampleEntries")
  @DisplayName("Should play an MP4 file over HLS when its entry does not list its sample entry")
  void shouldPlayAnMp4FileOverHlsWhenItsEntryDoesNotListItsSampleEntry(
      String scenario, Optional<String> storedSampleEntry) {
    var facts = h264Facts().sampleEntry(storedSampleEntry).build();
    var request =
        request()
            .capabilityDeclaration(isoDeclarationListing(SourceContainer.MP4))
            .source(isoSource(Optional.of("isom")).videoPlaybackFacts(Optional.of(facts)).build());

    assertThat(decidedContainer(request.build())).isEmpty();
  }

  static Stream<Arguments> sampleEntries() {
    return Stream.of(
        Arguments.of("avc3, which the entry does not list", Optional.of("avc3")),
        Arguments.of("no sample entry, as before probe version 7", Optional.empty()));
  }

  @Test
  @DisplayName("Should play over HLS when the probe reports no container")
  void shouldPlayOverHlsWhenTheProbeReportsNoContainer() {
    var request = request().source(h264Source().containerFormat(Optional.empty()).build());

    assertThat(decidedContainer(request.build())).isEmpty();
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

    assertThat(decidedContainer(request.build())).isEmpty();
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
            decidedContainer(
                request()
                    .capabilityDeclaration(declaration)
                    .source(hevcSource(placedAfter))
                    .build()))
        .contains(SourceContainer.MATROSKA);
    assertThat(
            decidedContainer(
                request()
                    .capabilityDeclaration(declaration)
                    .source(hevcSource(startingSample))
                    .build()))
        .isEmpty();
  }

  // A native player may start any audio track, and the viewer can switch tracks inside the file,
  // so a forced image track in the language of any audio track would go unshown.
  @ParameterizedTest(name = "{0}")
  @MethodSource("forcedImageSubtitles")
  @DisplayName(
      "Should play over HLS only when a forced image subtitle matches an audio track's language")
  void shouldPlayOverHlsOnlyWhenAForcedImageSubtitleMatchesAnAudioTracksLanguage(
      String scenario, String subtitleLanguage, boolean directPlays) {
    var forced =
        StreamInfo.builder()
            .codecType("subtitle")
            .codec(Optional.of("hdmv_pgs_subtitle"))
            .language(Optional.of(subtitleLanguage))
            .isForced(true)
            .build();
    var source = h264Source().streams(List.of(aac("eng"), aac("fra"), forced)).build();
    var request =
        request().capabilityDeclaration(selectingDeclarationListing(SourceContainer.MATROSKA));

    assertThat(decidedContainer(request.source(source).build()).isPresent()).isEqualTo(directPlays);
  }

  static Stream<Arguments> forcedImageSubtitles() {
    return Stream.of(
        Arguments.of("forced track in the first audio track's language", "eng", false),
        Arguments.of("forced track in the second audio track's language", "fra", false),
        Arguments.of("forced track in an alias of the second audio track's code", "fre", false),
        Arguments.of("forced track in no audio track's language", "deu", true));
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
            decidedContainer(
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

    assertThat(decidedContainer(request().viewerLimits(limits).build()))
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
    var request =
        request().capabilityDeclaration(selectingDeclarationListing(SourceContainer.MATROSKA));

    assertThat(decidedContainer(request.source(source).build())).isEmpty();
  }

  // ADR 0045: a player that cannot select an audio track in a directly played file, such as
  // Chromium's, direct-plays only a file with one audio track. A file with several plays over HLS
  // from the start, where an audio switch is a rendition switch.
  @Test
  @DisplayName(
      "Should play over HLS when the file has several audio tracks and the player cannot select"
          + " one")
  void shouldPlayOverHlsWhenTheFileHasSeveralAudioTracksAndThePlayerCannotSelectOne() {
    var source = h264Source().streams(List.of(aac("eng"), aac("fra"))).build();
    var request =
        request()
            .capabilityDeclaration(nonSelectingDeclarationListing(SourceContainer.MATROSKA))
            .source(source);

    assertThat(decidedContainer(request.build())).isEmpty();
  }

  @Test
  @DisplayName(
      "Should direct-play a file with one audio track when the player cannot select an audio track")
  void shouldDirectPlayAFileWithOneAudioTrackWhenThePlayerCannotSelectAnAudioTrack() {
    var request =
        request()
            .capabilityDeclaration(nonSelectingDeclarationListing(SourceContainer.MATROSKA))
            .source(h264Source().streams(List.of(aac("eng"))).build());

    assertThat(decidedContainer(request.build())).contains(SourceContainer.MATROSKA);
  }

  @Test
  @DisplayName(
      "Should direct-play a file with several audio tracks when the player selects an audio track")
  void shouldDirectPlayAFileWithSeveralAudioTracksWhenThePlayerSelectsAnAudioTrack() {
    var source = h264Source().streams(List.of(aac("eng"), aac("fra"))).build();
    var request =
        request()
            .capabilityDeclaration(selectingDeclarationListing(SourceContainer.MATROSKA))
            .source(source);

    assertThat(decidedContainer(request.build())).contains(SourceContainer.MATROSKA);
  }

  // The stream session plays the first audio stream that the probe lists, over HLS and in direct
  // play alike, and a choice of the session's audio track lists the chosen track first. The client
  // finds that track by its position among the file's audio tracks in stream order, which is the
  // order of a player's own track list.
  @ParameterizedTest(name = "{0}")
  @MethodSource("namedAudioTracks")
  @DisplayName(
      "Should name the session's audio track by its position among the file's audio tracks when the"
          + " file direct-plays")
  void shouldNameTheSessionsAudioTrackByItsPositionAmongTheFilesAudioTracksWhenTheFileDirectPlays(
      String scenario, List<StreamInfo> streams, OptionalInt expected) {
    var request =
        request()
            .capabilityDeclaration(selectingDeclarationListing(SourceContainer.MATROSKA))
            .source(h264Source().streams(streams).build());

    assertThat(service.decideDirectPlay(request.build()))
        .map(DirectPlayDecision::audioTrackPosition)
        .contains(expected);
  }

  static Stream<Arguments> namedAudioTracks() {
    var englishFirst = aacBuilder("eng").index(1).build();
    var frenchSecond = aacBuilder("fra").index(2).build();
    var subtitle = StreamInfo.builder().index(3).codecType("subtitle").build();
    return Stream.of(
        Arguments.of(
            "the first of two tracks", List.of(englishFirst, frenchSecond), OptionalInt.of(0)),
        Arguments.of(
            "a later track that the probe lists first",
            List.of(frenchSecond, englishFirst),
            OptionalInt.of(1)),
        Arguments.of("a file without audio", List.of(subtitle), OptionalInt.empty()));
  }

  private Optional<SourceContainer> decidedContainer(DirectPlayRequest request) {
    return service.decideDirectPlay(request).map(DirectPlayDecision::sourceContainer);
  }

  private static DirectPlayRequest.DirectPlayRequestBuilder request() {
    return DirectPlayRequest.builder()
        .source(h264Source().build())
        .capabilityDeclaration(declarationListing(List.of(SourceContainer.MATROSKA)))
        .videoFormatConstraint(VideoFormatConstraint.DIRECT_PLAY_OR_COPY_OR_ENCODE)
        .viewerLimits(ViewerLimits.builder().quality(VideoQuality.ORIGINAL).build());
  }

  private static ClientCapabilityDeclaration declarationListing(List<SourceContainer> containers) {
    return declarationBuilderListing(containers).build();
  }

  private static ClientCapabilityDeclaration.ClientCapabilityDeclarationBuilder
      declarationBuilderListing(List<SourceContainer> containers) {
    return ClientCapabilityDeclaration.builder()
        .video(List.of(h264EntryBuilder().directPlayContainers(containers).build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(containers).build()));
  }

  private static ClientCapabilityDeclaration selectingDeclarationListing(
      SourceContainer container) {
    return declarationBuilderListing(List.of(container))
        .selectsAudioTrackInDirectPlay(true)
        .build();
  }

  private static ClientCapabilityDeclaration nonSelectingDeclarationListing(
      SourceContainer container) {
    return declarationBuilderListing(List.of(container))
        .selectsAudioTrackInDirectPlay(false)
        .build();
  }

  // H.264 and AAC entries that list the container and the avc1 sample entry.
  private static ClientCapabilityDeclaration isoDeclarationListing(SourceContainer container) {
    return ClientCapabilityDeclaration.builder()
        .video(
            List.of(
                h264EntryBuilder()
                    .directPlayContainers(List.of(container))
                    .directPlaySampleEntries(List.of(VideoSampleEntry.AVC1))
                    .build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(List.of(container)).build()))
        .build();
  }

  // An H.264 file that ffprobe's MP4 and MOV demuxer read, stored under the avc1 sample entry.
  private static MediaProbe.MediaProbeBuilder isoSource(Optional<String> majorBrand) {
    return h264Source()
        .containerFormat(Optional.of(MOV_DEMUXER))
        .majorBrand(majorBrand)
        .videoPlaybackFacts(Optional.of(h264Facts().sampleEntry(Optional.of("avc1")).build()));
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
    return aac("eng");
  }

  private static StreamInfo aac(String language) {
    return aacBuilder(language).build();
  }

  private static StreamInfo.StreamInfoBuilder aacBuilder(String language) {
    return audioStreamBuilder("aac").channels(OptionalInt.of(2)).language(Optional.of(language));
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
