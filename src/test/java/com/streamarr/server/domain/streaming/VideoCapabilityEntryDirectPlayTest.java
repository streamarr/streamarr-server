package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.videoEntryBuilderFor;
import static com.streamarr.server.fixtures.VideoPlaybackFactsFixture.dolbyVisionProfile5;
import static com.streamarr.server.fixtures.VideoPlaybackFactsFixture.dolbyVisionProfile82;
import static com.streamarr.server.fixtures.VideoPlaybackFactsFixture.dolbyVisionProfile9;
import static com.streamarr.server.fixtures.VideoPlaybackFactsFixture.rangeWithoutEnhancement;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.VideoStreamCopyFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a video capability entry judges direct play of a file's video stream in its source container
 * (ADR 0049): the entry lists the container and accepts every property of the stream as it is.
 */
@Tag("UnitTest")
@DisplayName("Video Capability Entry Direct Play Tests")
class VideoCapabilityEntryDirectPlayTest {

  private static final SourceContainer CONTAINER = SourceContainer.MATROSKA;

  @Test
  @DisplayName("Should accept the stream when the entry lists its container and fits it")
  void shouldAcceptTheStreamWhenTheEntryListsItsContainerAndFitsIt() {
    assertThat(directPlayEntry().build().acceptsDirectPlay(hevcStream().build(), CONTAINER))
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should accept a High tier stream when the entry lists its container and fits its codec"
          + " profile and level")
  void shouldAcceptAHighTierStreamWhenTheEntryListsItsContainerAndFitsItsCodecProfileAndLevel() {
    var highTier =
        hevcStream()
            .videoProperties(
                Optional.of(
                    VideoStreamProperties.builder()
                        .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
                        .codecLevel(OptionalInt.of(51))
                        .codecTier(Optional.of(VideoCodecTier.HIGH))
                        .firstFramesRead(true)
                        .build()))
            .build();

    assertThat(directPlayEntry().build().directPlayRefusals(highTier, CONTAINER)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entriesThatRefuseTheStream")
  @DisplayName("Should refuse the stream when one property of the entry does not cover it")
  void shouldRefuseTheStreamWhenOnePropertyOfTheEntryDoesNotCoverIt(
      String scenario, VideoCapabilityEntry entry) {
    assertThat(entry.acceptsDirectPlay(hevcStream().build(), CONTAINER)).isFalse();
  }

  static Stream<Arguments> entriesThatRefuseTheStream() {
    return Stream.of(
        Arguments.of(
            "another container",
            directPlayEntry().directPlayContainers(List.of(SourceContainer.MPEG_TS)).build()),
        Arguments.of("HLS only", directPlayEntry().directPlayContainers(List.of()).build()),
        Arguments.of(
            "another codec family",
            h264EntryBuilder().directPlayContainers(List.of(CONTAINER)).build()),
        Arguments.of(
            "another codec profile",
            directPlayEntry()
                .codecProfiles(List.of(upToLevel(VideoCodecProfile.HEVC_MAIN, 51)))
                .build()),
        Arguments.of(
            "a lower codec level",
            directPlayEntry()
                .codecProfiles(List.of(upToLevel(VideoCodecProfile.HEVC_MAIN_10, 40)))
                .build()),
        Arguments.of("a lower bit depth", directPlayEntry().maxBitDepth(8).build()),
        Arguments.of("a smaller picture", directPlayEntry().maxWidth(1280).maxHeight(720).build()),
        Arguments.of("a lower frame rate", directPlayEntry().maxFrameRate(25).build()));
  }

  // #396: the decision shows each entry's refused checks, so each refusal names its check.
  @ParameterizedTest(name = "{0}")
  @MethodSource("refusedDirectPlayChecks")
  @DisplayName("Should name each check of direct play that the entry refuses when it refuses one")
  void shouldNameEachCheckOfDirectPlayThatTheEntryRefusesWhenItRefusesOne(
      String scenario,
      VideoCapabilityEntry entry,
      VideoStreamCopy stream,
      SourceContainer container,
      List<DecisionReason> expected) {
    assertThat(entry.directPlayRefusals(stream, container)).containsExactlyElementsOf(expected);
  }

  static Stream<Arguments> refusedDirectPlayChecks() {
    var pq = directPlayEntry().compatibleBases(List.of(CompatibleBase.PQ)).build();
    return Stream.of(
        Arguments.of(
            "another container, and a smaller picture that is not checked",
            directPlayEntry()
                .directPlayContainers(List.of(SourceContainer.MPEG_TS))
                .maxWidth(1280)
                .maxHeight(720)
                .build(),
            hevcStream().build(),
            CONTAINER,
            List.of(DecisionReason.DIRECT_PLAY_CONTAINER_NOT_DECLARED)),
        Arguments.of(
            "an MP4 stream under a sample entry that the entry does not list",
            entryListing(SourceContainer.MP4, VideoSampleEntry.HEV1),
            hevcStream().sampleEntry(Optional.of("hvc1")).build(),
            SourceContainer.MP4,
            List.of(DecisionReason.DIRECT_PLAY_SAMPLE_ENTRY_NOT_DECLARED)),
        Arguments.of(
            "a probe version 4 stream",
            directPlayEntry().build(),
            hevcStream().videoProperties(Optional.empty()).build(),
            CONTAINER,
            List.of(DecisionReason.VIDEO_PROPERTIES_NOT_PROBED)),
        Arguments.of(
            "a lower bit depth and a smaller picture",
            directPlayEntry().maxBitDepth(8).maxWidth(1280).maxHeight(720).build(),
            hevcStream().build(),
            CONTAINER,
            List.of(
                DecisionReason.VIDEO_BIT_DEPTH_NOT_DECLARED,
                DecisionReason.VIDEO_PICTURE_SIZE_NOT_DECLARED)),
        Arguments.of(
            "a PQ base and HDR10+ to an SDR entry",
            directPlayEntry().build(),
            hevcStream().dynamicRange(hdr10Plus()).build(),
            CONTAINER,
            List.of(
                DecisionReason.DYNAMIC_RANGE_NOT_DECLARED, DecisionReason.HDR10_PLUS_NOT_DECLARED)),
        Arguments.of(
            "profile 8.1 to a PQ entry listing no Dolby Vision profile",
            pq,
            hevcStream().dynamicRange(profile81()).build(),
            CONTAINER,
            List.of(DecisionReason.DOLBY_VISION_NOT_DECLARED)),
        Arguments.of(
            "Dolby Vision of an unknown profile",
            pq,
            hevcStream().dynamicRange(unknownProfile()).build(),
            CONTAINER,
            List.of(DecisionReason.DOLBY_VISION_PROFILE_OR_LEVEL_UNKNOWN)));
  }

  @Test
  @DisplayName("Should refuse the stream when the probe reports no codec profile facts")
  void shouldRefuseTheStreamWhenTheProbeReportsNoCodecProfileFacts() {
    var probeVersion4Stream = hevcStream().videoProperties(Optional.empty()).build();

    assertThat(directPlayEntry().build().acceptsDirectPlay(probeVersion4Stream, CONTAINER))
        .isFalse();
  }

  @Test
  @DisplayName("Should accept the stream when the frame rate is unknown and the rest fits")
  void shouldAcceptTheStreamWhenTheFrameRateIsUnknownAndTheRestFits() {
    var stream = hevcStream().frameRate(OptionalDouble.empty()).build();

    assertThat(directPlayEntry().build().acceptsDirectPlay(stream, CONTAINER)).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dynamicRanges")
  @DisplayName("Should accept the dynamic range only when the entry declares it")
  void shouldAcceptTheDynamicRangeOnlyWhenTheEntryDeclaresIt(
      String scenario, VideoCapabilityEntry entry, VideoDynamicRange range, boolean accepted) {
    var stream = hevcStream().dynamicRange(range).build();

    assertThat(entry.acceptsDirectPlay(stream, CONTAINER)).isEqualTo(accepted);
  }

  static Stream<Arguments> dynamicRanges() {
    var sdr = directPlayEntry().build();
    var pq = directPlayEntry().compatibleBases(List.of(CompatibleBase.PQ)).build();
    var pqWithHdr10Plus =
        directPlayEntry().compatibleBases(List.of(CompatibleBase.PQ)).hdr10Plus(true).build();
    var profile81AtLevel6 = pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 6);
    var everyProfile =
        directPlayEntry()
            .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
            .dolbyVisionProfiles(
                List.of(
                    dolbyVisionCapability(DolbyVisionProfile.PROFILE_5, 13),
                    dolbyVisionCapability(DolbyVisionProfile.PROFILE_8_1, 13),
                    dolbyVisionCapability(DolbyVisionProfile.PROFILE_8_4, 13)))
            .hdr10Plus(true)
            .build();
    return Stream.of(
        Arguments.of("PQ base, SDR entry", sdr, rangeWithoutEnhancement(CompatibleBase.PQ), false),
        Arguments.of("PQ base, PQ entry", pq, rangeWithoutEnhancement(CompatibleBase.PQ), true),
        Arguments.of("HDR10+, entry without HDR10+", pq, hdr10Plus(), false),
        Arguments.of("HDR10+, entry with HDR10+", pqWithHdr10Plus, hdr10Plus(), true),
        Arguments.of(
            "profile 8.1 at level 6, entry listing it at level 6",
            profile81AtLevel6,
            profile81(),
            true),
        Arguments.of(
            "profile 8.1 at level 6, entry listing it at level 5",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 5),
            profile81(),
            false),
        Arguments.of(
            "profile 8.1, PQ entry listing no Dolby Vision profile", pq, profile81(), false),
        Arguments.of(
            "profile 5, entry listing it",
            directPlayEntry()
                .compatibleBases(List.of())
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_5, 6)))
                .build(),
            dolbyVisionProfile5(),
            true),
        Arguments.of(
            "profile 5, entry listing it below the file's level",
            directPlayEntry()
                .compatibleBases(List.of())
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_5, 5)))
                .build(),
            dolbyVisionProfile5(),
            false),
        Arguments.of(
            "profile 5, entry listing only profile 8.1",
            profile81AtLevel6,
            dolbyVisionProfile5(),
            false),
        Arguments.of("profile 7, entry listing every profile", everyProfile, profile7(), false),
        Arguments.of(
            "Dolby Vision of an unknown profile, entry listing every profile",
            everyProfile,
            unknownProfile(),
            false));
  }

  // Matroska stores no sample entry, so a profile 8.2 or 9 stream in it plays as its SDR base.
  @ParameterizedTest(name = "{0}")
  @MethodSource("sdrBaseDolbyVisionInMatroska")
  @DisplayName(
      "Should accept a profile 8.2 or 9 stream in Matroska when the entry declares its base codec and SDR")
  void shouldAcceptAProfile82Or9StreamInMatroskaWhenTheEntryDeclaresItsBaseCodecAndSdr(
      String profile, VideoStreamCopy stream) {
    var sdrEntry = directPlayEntryFor(stream, SourceContainer.MATROSKA).build();

    assertThat(sdrEntry.acceptsDirectPlay(stream, SourceContainer.MATROSKA)).isTrue();
  }

  static Stream<Arguments> sdrBaseDolbyVisionInMatroska() {
    return Stream.of(
        Arguments.of("profile 8.2", hevcStream().dynamicRange(dolbyVisionProfile82()).build()),
        Arguments.of("profile 9", h264Stream().dynamicRange(dolbyVisionProfile9()).build()));
  }

  // Only Matroska, MP4 and MOV carry a profile 8.2 or 9 stream to direct play.
  @Test
  @DisplayName(
      "Should refuse a profile 8.2 stream in MPEG-TS when the entry declares its base codec and SDR")
  void shouldRefuseAProfile82StreamInMpegTsWhenTheEntryDeclaresItsBaseCodecAndSdr() {
    var stream = hevcStream().dynamicRange(dolbyVisionProfile82()).build();
    var sdrEntry = directPlayEntryFor(stream, SourceContainer.MPEG_TS).build();

    assertThat(sdrEntry.acceptsDirectPlay(stream, SourceContainer.MPEG_TS)).isFalse();
  }

  // A profile 8.2 or 9 stream plays as its SDR base and keeps its RPUs, so in MP4 or MOV it plays
  // directly under the sample entry of that base, for an entry that declares no Dolby Vision
  // profile.
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("sdrBaseDolbyVisionUnderTheBaseSampleEntry")
  @DisplayName(
      "Should accept a profile 8.2 or 9 stream in MP4 or MOV when it is stored under its base sample entry")
  void shouldAcceptAProfile82Or9StreamInMp4OrMovWhenItIsStoredUnderItsBaseSampleEntry(
      String scenario,
      SourceContainer container,
      VideoStreamCopy stream,
      VideoSampleEntry baseSampleEntry) {
    var sdrEntry =
        directPlayEntryFor(stream, container)
            .directPlaySampleEntries(List.of(baseSampleEntry))
            .build();

    assertThat(sdrEntry.acceptsDirectPlay(stream, container)).isTrue();
  }

  static Stream<Arguments> sdrBaseDolbyVisionUnderTheBaseSampleEntry() {
    return Stream.of(SourceContainer.MP4, SourceContainer.MOV)
        .flatMap(
            container ->
                Stream.of(
                    Arguments.of(
                        "profile 8.2 under hvc1",
                        container,
                        profile82StoredUnder("hvc1"),
                        VideoSampleEntry.HVC1),
                    Arguments.of(
                        "profile 9 under avc1",
                        container,
                        profile9StoredUnder("avc1"),
                        VideoSampleEntry.AVC1)));
  }

  // Under a Dolby Vision sample entry, players refuse a profile 8.2 or 9 stream rather than play
  // its SDR base. A stream under any sample entry other than its base's stays off direct play too,
  // whatever the entry lists, and plays over HLS under its base sample entry.
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("sdrBaseDolbyVisionUnderAnotherSampleEntry")
  @DisplayName(
      "Should refuse a profile 8.2 or 9 stream in MP4 or MOV when it is stored under another sample entry")
  void shouldRefuseAProfile82Or9StreamInMp4OrMovWhenItIsStoredUnderAnotherSampleEntry(
      String scenario, SourceContainer container, VideoStreamCopy stream) {
    var entryListingEverything =
        directPlayEntryFor(stream, container)
            .directPlaySampleEntries(List.of(VideoSampleEntry.values()))
            .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
            .dolbyVisionProfiles(
                Stream.of(DolbyVisionProfile.values())
                    .map(profile -> dolbyVisionCapability(profile, 13))
                    .toList())
            .build();

    assertThat(entryListingEverything.acceptsDirectPlay(stream, container)).isFalse();
  }

  static Stream<Arguments> sdrBaseDolbyVisionUnderAnotherSampleEntry() {
    return Stream.of(SourceContainer.MP4, SourceContainer.MOV)
        .flatMap(
            container ->
                Stream.of(
                    Arguments.of("profile 8.2 under dvh1", container, profile82StoredUnder("dvh1")),
                    Arguments.of("profile 8.2 under dvhe", container, profile82StoredUnder("dvhe")),
                    Arguments.of("profile 8.2 under hev1", container, profile82StoredUnder("hev1")),
                    Arguments.of("profile 9 under dvav", container, profile9StoredUnder("dvav")),
                    Arguments.of("profile 9 under dva1", container, profile9StoredUnder("dva1")),
                    Arguments.of("profile 9 under avc3", container, profile9StoredUnder("avc3"))));
  }

  // An MP4 or MOV file's video stream is covered only when the entry lists its sample entry
  // (ADR 0045).
  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("storedSampleEntries")
  @DisplayName("Should accept an MP4 or MOV stream only when the entry lists its sample entry")
  void shouldAcceptAnMp4OrMovStreamOnlyWhenTheEntryListsItsSampleEntry(
      String storedSampleEntry, SourceContainer container, boolean accepted) {
    var stream = hevcStream().sampleEntry(Optional.of(storedSampleEntry)).build();
    var entry = entryListing(container, VideoSampleEntry.HVC1, VideoSampleEntry.DVH1);

    assertThat(entry.acceptsDirectPlay(stream, container)).isEqualTo(accepted);
  }

  static Stream<Arguments> storedSampleEntries() {
    return Stream.of(
        Arguments.of("hvc1", SourceContainer.MP4, true),
        Arguments.of("hvc1", SourceContainer.MOV, true),
        Arguments.of("dvh1", SourceContainer.MP4, true),
        Arguments.of("hev1", SourceContainer.MP4, false),
        Arguments.of("hev1", SourceContainer.MOV, false),
        Arguments.of("HVC1", SourceContainer.MP4, false),
        Arguments.of("mp4v", SourceContainer.MP4, false));
  }

  // Probe version 7 reports no sample entry for a track with more than one, and an earlier probe
  // version reports none at all (ADR 0032).
  @ParameterizedTest
  @EnumSource(
      value = SourceContainer.class,
      names = {"MP4", "MOV"})
  @DisplayName("Should refuse an MP4 or MOV stream when the probe reports no sample entry")
  void shouldRefuseAnMp4OrMovStreamWhenTheProbeReportsNoSampleEntry(SourceContainer container) {
    var entry = entryListing(container, VideoSampleEntry.HVC1);

    assertThat(entry.acceptsDirectPlay(hevcStream().build(), container)).isFalse();
  }

  // The other source containers store no sample entry, so a stream in one is matched on its other
  // facts alone (ADR 0045).
  @Test
  @DisplayName("Should accept a Matroska stream when the entry lists no sample entry")
  void shouldAcceptAMatroskaStreamWhenTheEntryListsNoSampleEntry() {
    var entry = directPlayEntry().directPlaySampleEntries(List.of()).build();

    assertThat(entry.acceptsDirectPlay(hevcStream().build(), SourceContainer.MATROSKA)).isTrue();
  }

  private static VideoCapabilityEntry entryListing(
      SourceContainer container, VideoSampleEntry... sampleEntries) {
    return directPlayEntry()
        .directPlayContainers(List.of(container))
        .directPlaySampleEntries(List.of(sampleEntries))
        .build();
  }

  // An SDR direct-play entry of the stream's codec family that lists the container.
  private static VideoCapabilityEntry.VideoCapabilityEntryBuilder directPlayEntryFor(
      VideoStreamCopy stream, SourceContainer container) {
    return videoEntryBuilderFor(stream.codecFamily())
        .hls(false)
        .directPlayContainers(List.of(container));
  }

  private static VideoCapabilityEntry.VideoCapabilityEntryBuilder directPlayEntry() {
    return hevcEntryBuilder().hls(false).directPlayContainers(List.of(CONTAINER));
  }

  private static VideoStreamCopy.VideoStreamCopyBuilder hevcStream() {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder();
  }

  private static VideoStreamCopy.VideoStreamCopyBuilder h264Stream() {
    return VideoStreamCopyFixture.sdrH264CopyBuilder();
  }

  private static VideoStreamCopy profile82StoredUnder(String sampleEntry) {
    return hevcStream()
        .dynamicRange(dolbyVisionProfile82())
        .sampleEntry(Optional.of(sampleEntry))
        .build();
  }

  private static VideoStreamCopy profile9StoredUnder(String sampleEntry) {
    return h264Stream()
        .dynamicRange(dolbyVisionProfile9())
        .sampleEntry(Optional.of(sampleEntry))
        .build();
  }

  private static VideoCodecProfileCapability upToLevel(VideoCodecProfile profile, int maxLevel) {
    return VideoCodecProfileCapability.builder().profile(profile).maxLevel(maxLevel).build();
  }

  private static VideoCapabilityEntry pqEntryListing(DolbyVisionProfile profile, int maxLevel) {
    return directPlayEntry()
        .compatibleBases(List.of(CompatibleBase.PQ))
        .dolbyVisionProfiles(List.of(dolbyVisionCapability(profile, maxLevel)))
        .build();
  }

  private static DolbyVisionProfileCapability dolbyVisionCapability(
      DolbyVisionProfile profile, int maxLevel) {
    return DolbyVisionProfileCapability.builder().profile(profile).maxLevel(maxLevel).build();
  }

  private static VideoDynamicRange hdr10Plus() {
    return new VideoDynamicRange(CompatibleBase.PQ, Presence.PRESENT, new DolbyVision.Absent());
  }

  // Profile 8.1: a PQ base whose record claims compatibility identifier 1, at Dolby Vision level 6.
  private static VideoDynamicRange profile81() {
    return dolbyVision(
        CompatibleBase.PQ,
        Optional.of(
            DolbyVisionConfiguration.builder()
                .profile(OptionalInt.of(8))
                .level(OptionalInt.of(6))
                .baseLayerCompatibilityId(OptionalInt.of(1))
                .baseLayer(true)
                .rpu(true)
                .build()),
        Optional.empty());
  }

  // Without a record, an RPU header for an enhancement layer names profile 7.
  private static VideoDynamicRange profile7() {
    return dolbyVision(
        CompatibleBase.PQ,
        Optional.empty(),
        Optional.of(
            DolbyVisionRpuHeader.builder()
                .vdrRpuProfile(1)
                .enhancementLayerSpatialResamplingFilter(true)
                .disableResidual(false)
                .vdrBitDepth(12)
                .build()));
  }

  // Neither a record nor an RPU header names the profile.
  private static VideoDynamicRange unknownProfile() {
    return dolbyVision(CompatibleBase.PQ, Optional.empty(), Optional.empty());
  }

  private static VideoDynamicRange dolbyVision(
      CompatibleBase base,
      Optional<DolbyVisionConfiguration> configuration,
      Optional<DolbyVisionRpuHeader> header) {
    return new VideoDynamicRange(
        base,
        Presence.ABSENT,
        new DolbyVision.Present(
            configuration, Presence.PRESENT, header, DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
  }
}
