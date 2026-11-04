package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.VideoPlaybackFactsFixture.dolbyVisionProfile5;
import static com.streamarr.server.fixtures.VideoPlaybackFactsFixture.dolbyVisionProfile82;
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
                .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN, 51)))
                .build()),
        Arguments.of(
            "a lower codec level",
            directPlayEntry()
                .codecProfiles(List.of(mainTier(VideoCodecProfile.HEVC_MAIN_10, 40)))
                .build()),
        Arguments.of("a lower bit depth", directPlayEntry().maxBitDepth(8).build()),
        Arguments.of("a smaller picture", directPlayEntry().maxWidth(1280).maxHeight(720).build()),
        Arguments.of("a lower frame rate", directPlayEntry().maxFrameRate(25).build()));
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

  @Test
  @DisplayName(
      "Should accept a profile 8.2 stream in Matroska when the entry declares its base codec and SDR")
  void shouldAcceptAProfile82StreamInMatroskaWhenTheEntryDeclaresItsBaseCodecAndSdr() {
    var stream = hevcStream().dynamicRange(dolbyVisionProfile82()).build();

    assertThat(directPlayEntry().build().acceptsDirectPlay(stream, SourceContainer.MATROSKA))
        .isTrue();
  }

  // Until the probe reports the source sample entry, a profile 8.2 file in MP4 or MOV may be tagged
  // dvh1, which no client plays as its SDR base (streamarr/streamarr-server#395).
  @ParameterizedTest
  @EnumSource(
      value = SourceContainer.class,
      names = {"MP4", "MOV"})
  @DisplayName("Should refuse a profile 8.2 stream when its container names a sample entry")
  void shouldRefuseAProfile82StreamWhenItsContainerNamesASampleEntry(SourceContainer container) {
    var stream = hevcStream().dynamicRange(dolbyVisionProfile82()).build();
    var entry = directPlayEntry().directPlayContainers(List.of(container)).build();

    assertThat(entry.acceptsDirectPlay(stream, container)).isFalse();
  }

  private static VideoCapabilityEntry.VideoCapabilityEntryBuilder directPlayEntry() {
    return hevcEntryBuilder().hls(false).directPlayContainers(List.of(CONTAINER));
  }

  private static VideoStreamCopy.VideoStreamCopyBuilder hevcStream() {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder();
  }

  private static VideoCodecProfileCapability mainTier(VideoCodecProfile profile, int maxLevel) {
    return VideoCodecProfileCapability.builder()
        .profile(profile)
        .maxLevel(maxLevel)
        .maxTier(Optional.of(VideoCodecTier.MAIN))
        .build();
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
