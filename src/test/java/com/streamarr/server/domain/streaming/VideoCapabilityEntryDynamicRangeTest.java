package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.VideoStreamCopyFixture;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a video capability entry judges the dynamic range of an HLS stream copy as it is (ADR 0049).
 * A copy with Dolby Vision fits only an entry that lists its Dolby Vision profile at or above its
 * Dolby Vision level, except a profile 8.2 or 9 copy, which keeps its RPUs under an SDR base. A
 * copy with HDR10+ fits only an entry that declares HDR10+.
 */
@Tag("UnitTest")
@DisplayName("Video Capability Entry Dynamic Range Tests")
class VideoCapabilityEntryDynamicRangeTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("compatibleBases")
  @DisplayName("Should accept a compatible base only when the entry lists it")
  void shouldAcceptACompatibleBaseOnlyWhenTheEntryListsIt(
      String scenario, VideoCapabilityEntry entry, VideoStreamCopy copy, boolean accepted) {
    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> compatibleBases() {
    var sdr = hevcEntryBuilder().build();
    var pqOnly = hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build();
    var sdrAndPq =
        hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ)).build();
    var hlg = hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.HLG)).build();
    return Stream.of(
        Arguments.of(
            "unspecified base, SDR entry", sdr, hevcCopy(plain(CompatibleBase.UNSPECIFIED)), true),
        Arguments.of(
            "unspecified base, PQ entry",
            pqOnly,
            hevcCopy(plain(CompatibleBase.UNSPECIFIED)),
            false),
        Arguments.of("SDR base, PQ entry", pqOnly, hevcCopy(plain(CompatibleBase.SDR)), false),
        Arguments.of("PQ base, SDR entry", sdr, hevcCopy(plain(CompatibleBase.PQ)), false),
        Arguments.of(
            "PQ base, SDR and PQ entry", sdrAndPq, hevcCopy(plain(CompatibleBase.PQ)), true),
        Arguments.of("HLG base, HLG entry", hlg, hevcCopy(plain(CompatibleBase.HLG)), true),
        Arguments.of(
            "HLG base, SDR and PQ entry", sdrAndPq, hevcCopy(plain(CompatibleBase.HLG)), false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hdr10PlusCopies")
  @DisplayName("Should accept HDR10+ information only when the entry declares HDR10+")
  void shouldAcceptHdr10PlusInformationOnlyWhenTheEntryDeclaresHdr10Plus(
      String scenario, Presence hdr10Plus, boolean entryDeclaresHdr10Plus, boolean accepted) {
    var entry =
        hevcEntryBuilder()
            .compatibleBases(List.of(CompatibleBase.PQ))
            .hdr10Plus(entryDeclaresHdr10Plus)
            .build();
    var copy =
        hevcCopy(new VideoDynamicRange(CompatibleBase.PQ, hdr10Plus, new DolbyVision.Absent()));

    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> hdr10PlusCopies() {
    return Stream.of(
        Arguments.of("present, declared", Presence.PRESENT, true, true),
        Arguments.of("present, not declared", Presence.PRESENT, false, false),
        Arguments.of("absent, not declared", Presence.ABSENT, false, true),
        Arguments.of("unspecified, not declared", Presence.UNSPECIFIED, false, true));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopies")
  @DisplayName(
      "Should accept Dolby Vision only when the entry lists its profile at or above its level")
  void shouldAcceptDolbyVisionOnlyWhenTheEntryListsItsProfileAtOrAboveItsLevel(
      String scenario, VideoCapabilityEntry entry, VideoStreamCopy copy, boolean accepted) {
    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> dolbyVisionCopies() {
    var pqWith81AtLevel6 = pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 6);
    var profile81AtLevel6 =
        hevcCopy(
            declaredDolbyVision(
                CompatibleBase.PQ, configuration(8).baseLayerCompatibilityId(OptionalInt.of(1))));
    return Stream.of(
        Arguments.of("8.1 at its declared level", pqWith81AtLevel6, profile81AtLevel6, true),
        Arguments.of(
            "8.1 above its declared level while its HEVC level fits",
            pqWith81AtLevel6,
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.PQ,
                    configuration(8)
                        .baseLayerCompatibilityId(OptionalInt.of(1))
                        .level(OptionalInt.of(7)))),
            false),
        Arguments.of(
            "8.1, PQ entry without Dolby Vision",
            hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build(),
            profile81AtLevel6,
            false),
        Arguments.of(
            "8.1, entry listing 8.1 without the PQ base",
            hevcEntryBuilder()
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_8_1, 6)))
                .build(),
            profile81AtLevel6,
            false),
        Arguments.of(
            "8.1, entry listing 8.4",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_4, 6),
            profile81AtLevel6,
            false),
        Arguments.of(
            "8.1 without a record level",
            pqWith81AtLevel6,
            hevcCopy(dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader())),
            false),
        Arguments.of(
            "8.4 at its declared level",
            hevcEntryBuilder()
                .compatibleBases(List.of(CompatibleBase.HLG))
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_8_4, 6)))
                .build(),
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.HLG,
                    configuration(8).baseLayerCompatibilityId(OptionalInt.of(4)))),
            true),
        Arguments.of(
            "5 through its Dolby Vision profile alone",
            baselessEntryListing(DolbyVisionProfile.PROFILE_5, 6),
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.NONE,
                    configuration(5).baseLayerCompatibilityId(OptionalInt.of(0)))),
            true),
        Arguments.of(
            "5 above its declared level",
            baselessEntryListing(DolbyVisionProfile.PROFILE_5, 5),
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.NONE,
                    configuration(5).baseLayerCompatibilityId(OptionalInt.of(0)))),
            false),
        Arguments.of(
            "5, SDR and PQ entry without Dolby Vision",
            hevcEntryBuilder()
                .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                .build(),
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.NONE,
                    configuration(5).baseLayerCompatibilityId(OptionalInt.of(0)))),
            false),
        Arguments.of(
            "SDR copy, entry listing only profile 5",
            baselessEntryListing(DolbyVisionProfile.PROFILE_5, 6),
            hevcCopy(plain(CompatibleBase.SDR)),
            false),
        Arguments.of(
            "10.0 through its Dolby Vision profile alone",
            av1EntryBuilder()
                .compatibleBases(List.of())
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_10_0, 6)))
                .build(),
            av1Copy(
                declaredDolbyVision(
                    CompatibleBase.NONE,
                    configuration(10).baseLayerCompatibilityId(OptionalInt.of(0)))),
            true),
        Arguments.of(
            "10.1 at its declared level",
            av1EntryBuilder()
                .compatibleBases(List.of(CompatibleBase.PQ))
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_10_1, 6)))
                .build(),
            av1Copy(
                declaredDolbyVision(
                    CompatibleBase.PQ,
                    configuration(10).baseLayerCompatibilityId(OptionalInt.of(1)))),
            true),
        Arguments.of(
            "10 on an SDR base, which no entry can name",
            av1EntryBuilder().build(),
            av1Copy(
                declaredDolbyVision(
                    CompatibleBase.SDR,
                    configuration(10).baseLayerCompatibilityId(OptionalInt.of(2)))),
            false),
        Arguments.of(
            "7 on a PQ base",
            pqWith81AtLevel6,
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.PQ,
                    configuration(7).baseLayerCompatibilityId(OptionalInt.of(6)))),
            false),
        Arguments.of(
            "7 named by its RPU header",
            pqWith81AtLevel6,
            hevcCopy(dolbyVision(CompatibleBase.PQ, enhancementLayerRpuHeader())),
            false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sdrBaseDolbyVisionCopies")
  @DisplayName(
      "Should accept an SDR-base Dolby Vision copy only when the entry accepts its base codec and"
          + " SDR, whatever it declares about Dolby Vision")
  void shouldAcceptAnSdrBaseDolbyVisionCopyOnlyWhenTheEntryAcceptsItsBaseCodecAndSdr(
      String scenario, VideoCapabilityEntry entry, VideoStreamCopy copy, boolean accepted) {
    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> sdrBaseDolbyVisionCopies() {
    return Stream.of(
        Arguments.of(
            "8.2 from its record",
            hevcEntryBuilder().build(),
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.SDR,
                    configuration(8).baseLayerCompatibilityId(OptionalInt.of(2)))),
            true),
        Arguments.of(
            "8.2 from its RPU header",
            hevcEntryBuilder().build(),
            hevcCopy(dolbyVision(CompatibleBase.SDR, singleLayerRpuHeader())),
            true),
        Arguments.of(
            "8.2, PQ entry",
            hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build(),
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.SDR,
                    configuration(8).baseLayerCompatibilityId(OptionalInt.of(2)))),
            false),
        Arguments.of(
            "9 from its record",
            h264EntryBuilder().build(),
            h264Copy(
                declaredDolbyVision(
                    CompatibleBase.SDR,
                    configuration(9).baseLayerCompatibilityId(OptionalInt.of(2)))),
            true),
        Arguments.of(
            "9 from its RPU header",
            h264EntryBuilder().build(),
            h264Copy(dolbyVision(CompatibleBase.SDR, singleLayerRpuHeader())),
            true),
        Arguments.of(
            "7 named by its RPU header on an SDR base",
            hevcEntryBuilder().build(),
            hevcCopy(dolbyVision(CompatibleBase.SDR, enhancementLayerRpuHeader())),
            false),
        Arguments.of(
            "an RPU header that names no profile",
            hevcEntryBuilder().build(),
            hevcCopy(
                dolbyVision(
                    CompatibleBase.SDR,
                    rpuHeader(
                        DolbyVisionRpuHeader.builder().vdrRpuProfile(0).vdrBitDepth(10).build()))),
            false),
        Arguments.of(
            "unknown profile, with neither a record nor an RPU header",
            hevcEntryBuilder().build(),
            hevcCopy(
                new VideoDynamicRange(
                    CompatibleBase.SDR,
                    Presence.UNSPECIFIED,
                    new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED))),
            false));
  }

  private static VideoCapabilityEntry pqEntryListing(DolbyVisionProfile profile, int maxLevel) {
    return hevcEntryBuilder()
        .compatibleBases(List.of(CompatibleBase.PQ))
        .dolbyVisionProfiles(List.of(dolbyVisionCapability(profile, maxLevel)))
        .build();
  }

  private static VideoCapabilityEntry baselessEntryListing(
      DolbyVisionProfile profile, int maxLevel) {
    return hevcEntryBuilder()
        .compatibleBases(List.of())
        .dolbyVisionProfiles(List.of(dolbyVisionCapability(profile, maxLevel)))
        .build();
  }

  private static DolbyVisionProfileCapability dolbyVisionCapability(
      DolbyVisionProfile profile, int maxLevel) {
    return DolbyVisionProfileCapability.builder().profile(profile).maxLevel(maxLevel).build();
  }

  private static VideoStreamCopy hevcCopy(VideoDynamicRange range) {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder().dynamicRange(range).build();
  }

  private static VideoStreamCopy av1Copy(VideoDynamicRange range) {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder()
        .codecFamily(ProbedCodecFamily.AV1)
        .videoProperties(
            Optional.of(codedAs(VideoCodecProfile.AV1_MAIN, Optional.of(VideoCodecTier.MAIN))))
        .dynamicRange(range)
        .build();
  }

  private static VideoStreamCopy h264Copy(VideoDynamicRange range) {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder()
        .codecFamily(ProbedCodecFamily.H264)
        .videoProperties(Optional.of(codedAs(VideoCodecProfile.H264_HIGH, Optional.empty())))
        .bitDepth(OptionalInt.of(8))
        .dynamicRange(range)
        .build();
  }

  private static VideoStreamProperties codedAs(
      VideoCodecProfile profile, Optional<VideoCodecTier> tier) {
    return VideoStreamProperties.builder()
        .codecProfile(Optional.of(profile))
        .codecLevel(OptionalInt.of(41))
        .codecTier(tier)
        .firstFramesRead(true)
        .build();
  }

  private static VideoDynamicRange plain(CompatibleBase base) {
    return new VideoDynamicRange(base, Presence.ABSENT, new DolbyVision.Absent());
  }

  private static VideoDynamicRange dolbyVision(CompatibleBase base, DolbyVision.Present present) {
    return new VideoDynamicRange(base, Presence.ABSENT, present);
  }

  // Dolby Vision whose configuration record the caller completes with the base-layer
  // compatibility identifier.
  private static VideoDynamicRange declaredDolbyVision(
      CompatibleBase base, DolbyVisionConfiguration.DolbyVisionConfigurationBuilder configuration) {
    return dolbyVision(
        base,
        new DolbyVision.Present(
            Optional.of(configuration.build()),
            Presence.PRESENT,
            Optional.empty(),
            DolbyVisionRpuPlacement.AFTER_OWN_PICTURE));
  }

  // A configuration record that names the profile at Dolby Vision level 6.
  private static DolbyVisionConfiguration.DolbyVisionConfigurationBuilder configuration(
      int profile) {
    return DolbyVisionConfiguration.builder()
        .profile(OptionalInt.of(profile))
        .level(OptionalInt.of(6))
        .baseLayer(true)
        .rpu(true);
  }

  // Without a record, an RPU header with vdr_rpu_profile 1 and no enhancement layer names a single
  // layer with a compatible base: profile 8 in HEVC and profile 9 in H.264.
  private static DolbyVision.Present singleLayerRpuHeader() {
    return rpuHeader(
        DolbyVisionRpuHeader.builder()
            .vdrRpuProfile(1)
            .disableResidual(true)
            .vdrBitDepth(12)
            .build());
  }

  // Without a record, an RPU header for an enhancement layer at 12 bits names profile 7.
  private static DolbyVision.Present enhancementLayerRpuHeader() {
    return rpuHeader(
        DolbyVisionRpuHeader.builder()
            .vdrRpuProfile(1)
            .enhancementLayerSpatialResamplingFilter(true)
            .disableResidual(false)
            .vdrBitDepth(12)
            .build());
  }

  private static DolbyVision.Present rpuHeader(DolbyVisionRpuHeader header) {
    return new DolbyVision.Present(
        Optional.empty(),
        Presence.PRESENT,
        Optional.of(header),
        DolbyVisionRpuPlacement.AFTER_OWN_PICTURE);
  }
}
