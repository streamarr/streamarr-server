package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.aacEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.CompatibleBase;
import com.streamarr.server.domain.streaming.DecisionReason;
import com.streamarr.server.domain.streaming.DolbyVision;
import com.streamarr.server.domain.streaming.DolbyVisionConfiguration;
import com.streamarr.server.domain.streaming.DolbyVisionProfile;
import com.streamarr.server.domain.streaming.DolbyVisionProfileCapability;
import com.streamarr.server.domain.streaming.DolbyVisionRpuHeader;
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
  private static final DirectPlayChoice MATROSKA_DIRECT_PLAY =
      new DirectPlayChoice.Accepted(SourceContainer.MATROSKA, OptionalInt.of(0));

  private final TranscodeDecisionService service =
      new TranscodeDecisionService(
          StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build(),
          new SimpleMeterRegistry());

  @Test
  @DisplayName("Should direct-play the source in Original when the declaration covers the file")
  void shouldDirectPlayTheSourceInOriginalWhenTheDeclarationCoversTheFile() {
    assertThat(service.decideDirectPlay(request().build())).isEqualTo(MATROSKA_DIRECT_PLAY);
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"ORIGINAL"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should play over HLS and name no refusal when the playback choice is not Original")
  void shouldPlayOverHlsAndNameNoRefusalWhenThePlaybackChoiceIsNotOriginal(VideoQuality quality) {
    var request = request().viewerLimits(ViewerLimits.builder().quality(quality).build()).build();

    assertThat(service.decideDirectPlay(request)).isEqualTo(refused());
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoFormatConstraint.class,
      names = {"DIRECT_PLAY_OR_COPY_OR_ENCODE"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName(
      "Should play over HLS and name no refusal when the format attempt may not direct-play")
  void shouldPlayOverHlsAndNameNoRefusalWhenTheFormatAttemptMayNotDirectPlay(
      VideoFormatConstraint constraint) {
    assertThat(service.decideDirectPlay(request().videoFormatConstraint(constraint).build()))
        .isEqualTo(refused());
  }

  @Test
  @DisplayName("Should name the container when the declaration does not list the file's container")
  void shouldNameTheContainerWhenTheDeclarationDoesNotListTheFilesContainer() {
    var request = request().source(h264Source().containerFormat(Optional.of("mpegts")).build());

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(refused(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED));
  }

  @Test
  @DisplayName("Should name an unknown container when the probe's demuxer names no video container")
  void shouldNameAnUnknownContainerWhenTheProbesDemuxerNamesNoVideoContainer() {
    var everyContainer = declarationListing(List.of(SourceContainer.values()));
    var request =
        request()
            .capabilityDeclaration(everyContainer)
            .source(h264Source().containerFormat(Optional.of("wav")).build());

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(refused(DecisionReason.SOURCE_CONTAINER_UNKNOWN));
  }

  // The major brand tells MP4 from MOV: QuickTime's brand makes MOV, every other brand makes MP4,
  // and a file without an ftyp box counts as MP4 (ADR 0045).
  @ParameterizedTest(name = "major brand {0} in an entry for {1}")
  @MethodSource("majorBrands")
  @DisplayName(
      "Should direct-play an MP4 or MOV file only when its entry lists the brand's container")
  void shouldDirectPlayAnMp4OrMovFileOnlyWhenItsEntryListsTheBrandsContainer(
      Optional<String> majorBrand, SourceContainer listed, DirectPlayChoice expected) {
    var request =
        request()
            .capabilityDeclaration(isoDeclarationListing(listed))
            .source(isoSource(majorBrand).build());

    assertThat(service.decideDirectPlay(request.build())).isEqualTo(expected);
  }

  static Stream<Arguments> majorBrands() {
    var mp4 = new DirectPlayChoice.Accepted(SourceContainer.MP4, OptionalInt.of(0));
    var mov = new DirectPlayChoice.Accepted(SourceContainer.MOV, OptionalInt.of(0));
    var otherContainer = refused(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED);
    return Stream.of(
        Arguments.of(Optional.of("isom"), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("mp42"), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("M4V "), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("3gp4"), SourceContainer.MP4, mp4),
        Arguments.of(Optional.empty(), SourceContainer.MP4, mp4),
        Arguments.of(Optional.of("qt  "), SourceContainer.MOV, mov),
        Arguments.of(Optional.of("qt  "), SourceContainer.MP4, otherContainer),
        Arguments.of(Optional.of("isom"), SourceContainer.MOV, otherContainer),
        Arguments.of(Optional.empty(), SourceContainer.MOV, otherContainer));
  }

  // An MP4 or MOV file is covered only under a sample entry that the entry lists, and a file probed
  // before version 7 has no stored sample entry (ADR 0045).
  @ParameterizedTest(name = "{0}")
  @MethodSource("sampleEntries")
  @DisplayName("Should name the sample entry when an MP4 file's entry does not list it")
  void shouldNameTheSampleEntryWhenAnMp4FilesEntryDoesNotListIt(
      String scenario, Optional<String> storedSampleEntry) {
    var facts = h264Facts().sampleEntry(storedSampleEntry).build();
    var request =
        request()
            .capabilityDeclaration(isoDeclarationListing(SourceContainer.MP4))
            .source(isoSource(Optional.of("isom")).videoPlaybackFacts(Optional.of(facts)).build());

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(refused(DecisionReason.DIRECT_PLAY_SAMPLE_ENTRY_NOT_DECLARED));
  }

  static Stream<Arguments> sampleEntries() {
    return Stream.of(
        Arguments.of("avc3, which the entry does not list", Optional.of("avc3")),
        Arguments.of("no sample entry, as before probe version 7", Optional.empty()));
  }

  // A profile 8.2 or 9 file plays as its SDR base and keeps its RPUs, so an SDR declaration without
  // Dolby Vision profiles direct-plays it under its base sample entry. Under a Dolby Vision sample
  // entry, players refuse it, so it plays over HLS under its base sample entry instead.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sdrBaseDolbyVisionFiles")
  @DisplayName(
      "Should direct-play a profile 8.2 or 9 MP4 file only when it is stored under its base sample entry")
  void shouldDirectPlayAProfile82Or9Mp4FileOnlyWhenItIsStoredUnderItsBaseSampleEntry(
      String scenario, MediaProbe source, DirectPlayChoice expected) {
    var request =
        request()
            .capabilityDeclaration(sdrDeclarationListingEverySampleEntryInMp4())
            .source(source);

    assertThat(service.decideDirectPlay(request.build())).isEqualTo(expected);
  }

  static Stream<Arguments> sdrBaseDolbyVisionFiles() {
    var mp4 = new DirectPlayChoice.Accepted(SourceContainer.MP4, OptionalInt.of(0));
    var videoRefused = refused(DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED);
    return Stream.of(
        Arguments.of("profile 8.2 under hvc1", profile82Mp4File("hvc1"), mp4),
        Arguments.of("profile 8.2 under dvh1", profile82Mp4File("dvh1"), videoRefused),
        Arguments.of("profile 9 under avc1", profile9Mp4File("avc1"), mp4),
        Arguments.of("profile 9 under dvav", profile9Mp4File("dvav"), videoRefused));
  }

  // Direct play sends the file's own configuration record, so only a record that names the level
  // vouches for it. A level that the table derives from the maximum frame rate needs the record
  // that an HLS copy writes (ADR 0013).
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionMp4Files")
  @DisplayName(
      "Should direct-play a Dolby Vision MP4 file only when its configuration record names its"
          + " level")
  void shouldDirectPlayADolbyVisionMp4FileOnlyWhenItsConfigurationRecordNamesItsLevel(
      String scenario,
      VideoDynamicRange range,
      Optional<Fraction> maximumFrameRate,
      DirectPlayChoice expected) {
    var facts =
        hevcFacts(range).sampleEntry(Optional.of("dvh1")).maximumFrameRate(maximumFrameRate);
    var source =
        isoSource(Optional.of("isom"))
            .videoCodec("hevc")
            .videoPlaybackFacts(Optional.of(facts.build()))
            .build();
    var request =
        request().capabilityDeclaration(dolbyVisionDeclarationListingMp4()).source(source).build();

    assertThat(service.decideDirectPlay(request)).isEqualTo(expected);
  }

  static Stream<Arguments> dolbyVisionMp4Files() {
    var film = Optional.of(new Fraction(24, 1));
    return Stream.of(
        Arguments.of(
            "profile 5 with a record at level 6",
            profile5(
                Optional.of(
                    DolbyVisionConfiguration.builder()
                        .profile(OptionalInt.of(5))
                        .level(OptionalInt.of(6))
                        .baseLayerCompatibilityId(OptionalInt.of(0))
                        .baseLayer(true)
                        .rpu(true)
                        .build())),
            film,
            new DirectPlayChoice.Accepted(SourceContainer.MP4, OptionalInt.of(0))),
        Arguments.of(
            "profile 5 without a record, with a maximum frame rate that gives a level",
            profile5(Optional.empty()),
            film,
            refused(DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED)),
        Arguments.of(
            "profile 5 without a record or a maximum frame rate, so without a level",
            profile5(Optional.empty()),
            Optional.empty(),
            refused(DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED)),
        Arguments.of(
            "profile 8.1 without a record, with a maximum frame rate that gives a level",
            profile81FromItsRpuHeader(),
            film,
            refused(DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED)));
  }

  @Test
  @DisplayName("Should name an unknown container when the probe reports no container")
  void shouldNameAnUnknownContainerWhenTheProbeReportsNoContainer() {
    var request = request().source(h264Source().containerFormat(Optional.empty()).build());

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(refused(DecisionReason.SOURCE_CONTAINER_UNKNOWN));
  }

  @Test
  @DisplayName("Should name the interlaced source when the video is interlaced")
  void shouldNameTheInterlacedSourceWhenTheVideoIsInterlaced() {
    var interlaced =
        h264Facts()
            .frameRateRule(
                VideoPlaybackFactsFixture.interlacedRateRule(new Fraction(25, 1), FieldOrder.TT))
            .build();
    var request =
        request().source(h264Source().videoPlaybackFacts(Optional.of(interlaced)).build());

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(refused(DecisionReason.SOURCE_INTERLACED));
  }

  @Test
  @DisplayName("Should name the property issue when it rules out a copy of the video")
  void shouldNameThePropertyIssueWhenItRulesOutACopyOfTheVideo() {
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
        .isEqualTo(MATROSKA_DIRECT_PLAY);
    assertThat(
            service.decideDirectPlay(
                request()
                    .capabilityDeclaration(declaration)
                    .source(hevcSource(startingSample))
                    .build()))
        .isEqualTo(refused(DecisionReason.DOLBY_VISION_RPU_STARTS_SAMPLE));
  }

  // A native player may start any audio track, and the viewer can switch tracks inside the file,
  // so a forced image track in the language of any audio track would go unshown.
  @ParameterizedTest(name = "{0}")
  @MethodSource("forcedImageSubtitles")
  @DisplayName(
      "Should name the forced image subtitle only when it matches an audio track's language")
  void shouldNameTheForcedImageSubtitleOnlyWhenItMatchesAnAudioTracksLanguage(
      String scenario, String subtitleLanguage, DirectPlayChoice expected) {
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

    assertThat(service.decideDirectPlay(request.source(source).build())).isEqualTo(expected);
  }

  static Stream<Arguments> forcedImageSubtitles() {
    var forcedSubtitle = refused(DecisionReason.FORCED_IMAGE_SUBTITLE_IN_AUDIO_LANGUAGE);
    return Stream.of(
        Arguments.of("forced track in the first audio track's language", "eng", forcedSubtitle),
        Arguments.of("forced track in the second audio track's language", "fra", forcedSubtitle),
        Arguments.of(
            "forced track in an alias of the second audio track's code", "fre", forcedSubtitle),
        Arguments.of("forced track in no audio track's language", "deu", MATROSKA_DIRECT_PLAY));
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
        .isEqualTo(MATROSKA_DIRECT_PLAY);
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
        .isEqualTo(MATROSKA_DIRECT_PLAY);
  }

  @Test
  @DisplayName("Should name the audio when the declaration does not cover an audio track")
  void shouldNameTheAudioWhenTheDeclarationDoesNotCoverAnAudioTrack() {
    var source =
        h264Source()
            .streams(
                List.of(
                    englishAac(), audioStreamBuilder("ac3").channels(OptionalInt.of(6)).build()))
            .build();
    var request =
        request().capabilityDeclaration(selectingDeclarationListing(SourceContainer.MATROSKA));

    assertThat(service.decideDirectPlay(request.source(source).build()))
        .isEqualTo(refused(DecisionReason.DIRECT_PLAY_AUDIO_NOT_DECLARED));
  }

  // ADR 0045: a player that cannot select an audio track in a directly played Matroska file, such
  // as Chromium's or Firefox's, starts on the file's first audio track whatever its default flags
  // say. It direct-plays the file when the stream session's chosen audio track is the first, and
  // plays over HLS from the start otherwise, naming why. The probe lists the chosen track first, so
  // a later track listed first is a later choice.
  @ParameterizedTest(name = "{0}")
  @MethodSource("chosenAudioTracksForAPlayerThatCannotSelectOne")
  @DisplayName(
      "Should direct-play only when the chosen audio track is the first when the player cannot"
          + " select one")
  void shouldDirectPlayOnlyWhenTheChosenAudioTrackIsTheFirstWhenThePlayerCannotSelectOne(
      String scenario, List<StreamInfo> streams, DirectPlayChoice expected) {
    var request =
        request()
            .capabilityDeclaration(nonSelectingDeclarationListing(SourceContainer.MATROSKA))
            .source(h264Source().streams(streams).build());

    assertThat(service.decideDirectPlay(request.build())).isEqualTo(expected);
  }

  static Stream<Arguments> chosenAudioTracksForAPlayerThatCannotSelectOne() {
    var englishFirst = aacBuilder("eng").index(1).build();
    var frenchSecond = aacBuilder("fra").index(2).build();
    var frenchSecondAsDefault = aacBuilder("fra").index(2).isDefault(true).build();
    var notSelectable = refused(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE);
    return Stream.of(
        Arguments.of("one track", List.of(englishFirst), MATROSKA_DIRECT_PLAY),
        Arguments.of(
            "the first of two tracks", List.of(englishFirst, frenchSecond), MATROSKA_DIRECT_PLAY),
        Arguments.of(
            "the second of two tracks", List.of(frenchSecond, englishFirst), notSelectable),
        Arguments.of(
            "the first of two tracks when the second has the default flag",
            List.of(englishFirst, frenchSecondAsDefault),
            MATROSKA_DIRECT_PLAY));
  }

  // In an MP4 file, Chromium starts on the first audio track whose enabled flag is set, which the
  // probe reports as the default flag, and Firefox on the first audio track. When only a later
  // track is enabled, they start on different tracks, so the chosen first track plays over HLS.
  @Test
  @DisplayName(
      "Should name the unselectable audio track when an MP4 file enables only a later audio track")
  void shouldNameTheUnselectableAudioTrackWhenAnMp4FileEnablesOnlyALaterAudioTrack() {
    var streams =
        List.of(
            aacBuilder("eng").index(1).build(), aacBuilder("fra").index(2).isDefault(true).build());
    var request =
        request()
            .capabilityDeclaration(isoDeclarationListing(SourceContainer.MP4))
            .source(isoSource(Optional.of("isom")).streams(streams).build());

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(refused(DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE));
  }

  @Test
  @DisplayName(
      "Should name the unselectable audio track after the declaration's refusals when the file"
          + " fails both")
  void shouldNameTheUnselectableAudioTrackAfterTheDeclarationsRefusalsWhenTheFileFailsBoth() {
    var source =
        h264Source()
            .containerFormat(Optional.of("mpegts"))
            .streams(
                List.of(aacBuilder("fra").index(2).build(), aacBuilder("eng").index(1).build()))
            .build();
    var request =
        request()
            .capabilityDeclaration(nonSelectingDeclarationListing(SourceContainer.MATROSKA))
            .source(source);

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(
            refused(
                DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED,
                DecisionReason.DIRECT_PLAY_AUDIO_TRACK_NOT_SELECTABLE));
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

    assertThat(service.decideDirectPlay(request.build())).isEqualTo(MATROSKA_DIRECT_PLAY);
  }

  @Test
  @DisplayName("Should name the video when the entry that lists the container refuses the video")
  void shouldNameTheVideoWhenTheEntryThatListsTheContainerRefusesTheVideo() {
    var smallerPicture =
        h264EntryBuilder()
            .maxWidth(1280)
            .maxHeight(720)
            .directPlayContainers(List.of(SourceContainer.MATROSKA))
            .build();

    assertThat(
            service.decideDirectPlay(
                request().capabilityDeclaration(declarationWith(smallerPicture)).build()))
        .isEqualTo(refused(DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED));
  }

  @Test
  @DisplayName("Should name every refusal in check order when the file fails several checks")
  void shouldNameEveryRefusalInCheckOrderWhenTheFileFailsSeveralChecks() {
    var interlaced =
        h264Facts()
            .frameRateRule(
                VideoPlaybackFactsFixture.interlacedRateRule(new Fraction(25, 1), FieldOrder.TT))
            .build();
    var source =
        h264Source()
            .videoPlaybackFacts(Optional.of(interlaced))
            .streams(
                List.of(
                    englishAac(), audioStreamBuilder("ac3").channels(OptionalInt.of(6)).build()))
            .build();
    var smallerPicture =
        h264EntryBuilder()
            .maxWidth(1280)
            .maxHeight(720)
            .directPlayContainers(List.of(SourceContainer.MATROSKA))
            .build();
    var request = request().capabilityDeclaration(declarationWith(smallerPicture)).source(source);

    assertThat(service.decideDirectPlay(request.build()))
        .isEqualTo(
            refused(
                DecisionReason.SOURCE_INTERLACED,
                DecisionReason.DIRECT_PLAY_VIDEO_NOT_DECLARED,
                DecisionReason.DIRECT_PLAY_AUDIO_NOT_DECLARED));
  }

  @Test
  @DisplayName("Should name the container once when neither video nor audio entries list it")
  void shouldNameTheContainerOnceWhenNeitherVideoNorAudioEntriesListIt() {
    var source =
        h264Source()
            .containerFormat(Optional.of("mpegts"))
            .streams(
                List.of(
                    englishAac(), audioStreamBuilder("ac3").channels(OptionalInt.of(6)).build()))
            .build();

    var request =
        request().capabilityDeclaration(selectingDeclarationListing(SourceContainer.MATROSKA));

    assertThat(service.decideDirectPlay(request.source(source).build()))
        .isEqualTo(refused(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED));
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
        .isEqualTo(new DirectPlayChoice.Accepted(SourceContainer.MATROSKA, expected));
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

  private static DirectPlayChoice refused(DecisionReason... reasons) {
    return new DirectPlayChoice.Refused(List.of(reasons));
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

  // SDR HEVC and H.264 entries without Dolby Vision profiles that list MP4 and every sample entry.
  private static ClientCapabilityDeclaration sdrDeclarationListingEverySampleEntryInMp4() {
    var mp4 = List.of(SourceContainer.MP4);
    var everySampleEntry = List.of(VideoSampleEntry.values());
    return ClientCapabilityDeclaration.builder()
        .video(
            List.of(
                hevcEntryBuilder()
                    .directPlayContainers(mp4)
                    .directPlaySampleEntries(everySampleEntry)
                    .build(),
                h264EntryBuilder()
                    .directPlayContainers(mp4)
                    .directPlaySampleEntries(everySampleEntry)
                    .build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(mp4).build()))
        .build();
  }

  private static MediaProbe profile82Mp4File(String sampleEntry) {
    var facts =
        hevcFacts(VideoPlaybackFactsFixture.dolbyVisionProfile82())
            .sampleEntry(Optional.of(sampleEntry))
            .build();
    return isoSource(Optional.of("isom"))
        .videoCodec("hevc")
        .videoPlaybackFacts(Optional.of(facts))
        .build();
  }

  private static MediaProbe profile9Mp4File(String sampleEntry) {
    var facts =
        h264Facts()
            .dynamicRange(VideoPlaybackFactsFixture.dolbyVisionProfile9())
            .sampleEntry(Optional.of(sampleEntry))
            .build();
    return isoSource(Optional.of("isom")).videoPlaybackFacts(Optional.of(facts)).build();
  }

  // A player that selects an audio track, so that a file with several audio tracks may direct-play.
  private static ClientCapabilityDeclaration declarationWith(VideoCapabilityEntry video) {
    return ClientCapabilityDeclaration.builder()
        .video(List.of(video))
        .audio(
            List.of(
                aacEntryBuilder().directPlayContainers(List.of(SourceContainer.MATROSKA)).build()))
        .selectsAudioTrackInDirectPlay(true)
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
    return h264Source()
        .videoCodec("hevc")
        .videoPlaybackFacts(Optional.of(hevcFacts(range).build()));
  }

  private static VideoPlaybackFacts.VideoPlaybackFactsBuilder hevcFacts(VideoDynamicRange range) {
    return h264Facts()
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
                    .build()));
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

  // An HEVC entry that lists profiles 5 and 8.1 up to level 13, the PQ base, and MP4 with every
  // sample entry.
  private static ClientCapabilityDeclaration dolbyVisionDeclarationListingMp4() {
    var mp4 = List.of(SourceContainer.MP4);
    return ClientCapabilityDeclaration.builder()
        .video(
            List.of(
                hevcEntryBuilder()
                    .compatibleBases(List.of(CompatibleBase.PQ))
                    .dolbyVisionProfiles(
                        List.of(
                            DolbyVisionProfileCapability.builder()
                                .profile(DolbyVisionProfile.PROFILE_5)
                                .maxLevel(13)
                                .build(),
                            DolbyVisionProfileCapability.builder()
                                .profile(DolbyVisionProfile.PROFILE_8_1)
                                .maxLevel(13)
                                .build()))
                    .directPlayContainers(mp4)
                    .directPlaySampleEntries(List.of(VideoSampleEntry.values()))
                    .build()))
        .audio(List.of(aacEntryBuilder().directPlayContainers(mp4).build()))
        .build();
  }

  // Profile 5, which the record names or, without a record, the first RPU's header names. It has no
  // compatible base.
  private static VideoDynamicRange profile5(Optional<DolbyVisionConfiguration> configuration) {
    return new VideoDynamicRange(
        CompatibleBase.NONE,
        Presence.ABSENT,
        new DolbyVision.Present(
            configuration,
            Presence.PRESENT,
            Optional.of(
                DolbyVisionRpuHeader.builder()
                    .vdrRpuProfile(0)
                    .baseLayerFullRange(true)
                    .disableResidual(true)
                    .vdrBitDepth(12)
                    .build()),
            DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
  }

  // Without a record, the RPU header of a single-layer stream at 12 bits on a PQ base names
  // profile 8.1.
  private static VideoDynamicRange profile81FromItsRpuHeader() {
    return new VideoDynamicRange(
        CompatibleBase.PQ,
        Presence.ABSENT,
        new DolbyVision.Present(
            Optional.empty(),
            Presence.PRESENT,
            Optional.of(
                DolbyVisionRpuHeader.builder()
                    .vdrRpuProfile(1)
                    .disableResidual(true)
                    .vdrBitDepth(12)
                    .build()),
            DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
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
