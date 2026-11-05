package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.videoEntryBuilderFor;
import static com.streamarr.server.fixtures.VideoPlaybackFactsFixture.rangeWithoutEnhancement;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
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
import org.junit.jupiter.params.provider.MethodSource;

/**
 * How a video capability entry judges the dynamic range of an HLS stream copy as it is (ADR 0049).
 * A copy with Dolby Vision fits only an entry that lists its Dolby Vision profile at or above its
 * Dolby Vision level, except a profile 8.2 or 9 copy, which keeps its RPUs under an SDR base. A
 * profile 5 or 10.0 copy needs no compatible base in the entry. A copy with HDR10+ fits only an
 * entry that declares HDR10+.
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
            "unspecified base, SDR entry",
            sdr,
            hevcCopy(rangeWithoutEnhancement(CompatibleBase.UNSPECIFIED)),
            true),
        Arguments.of(
            "unspecified base, PQ entry",
            pqOnly,
            hevcCopy(rangeWithoutEnhancement(CompatibleBase.UNSPECIFIED)),
            false),
        Arguments.of(
            "SDR base, PQ entry",
            pqOnly,
            hevcCopy(rangeWithoutEnhancement(CompatibleBase.SDR)),
            false),
        Arguments.of(
            "PQ base, SDR entry", sdr, hevcCopy(rangeWithoutEnhancement(CompatibleBase.PQ)), false),
        Arguments.of(
            "PQ base, SDR and PQ entry",
            sdrAndPq,
            hevcCopy(rangeWithoutEnhancement(CompatibleBase.PQ)),
            true),
        Arguments.of(
            "HLG base, HLG entry",
            hlg,
            hevcCopy(rangeWithoutEnhancement(CompatibleBase.HLG)),
            true),
        Arguments.of(
            "HLG base, SDR and PQ entry",
            sdrAndPq,
            hevcCopy(rangeWithoutEnhancement(CompatibleBase.HLG)),
            false),
        Arguments.of(
            "SDR base, entry listing only Dolby Vision profile 5",
            baselessEntryListing(DolbyVisionProfile.PROFILE_5, 6),
            hevcCopy(rangeWithoutEnhancement(CompatibleBase.SDR)),
            false));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("hdr10PlusCopies")
  @DisplayName("Should accept HDR10+ information only when the entry declares HDR10+")
  void shouldAcceptHdr10PlusInformationOnlyWhenTheEntryDeclaresHdr10Plus(
      String scenario, VideoCapabilityEntry entry, VideoStreamCopy copy, boolean accepted) {
    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> hdr10PlusCopies() {
    var declaresHdr10Plus =
        hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).hdr10Plus(true).build();
    var withoutHdr10Plus =
        hevcEntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).hdr10Plus(false).build();
    return Stream.of(
        Arguments.of(
            "present, declared", declaresHdr10Plus, hevcCopy(hdr10Plus(Presence.PRESENT)), true),
        Arguments.of(
            "present, not declared",
            withoutHdr10Plus,
            hevcCopy(hdr10Plus(Presence.PRESENT)),
            false),
        Arguments.of(
            "absent, not declared", withoutHdr10Plus, hevcCopy(hdr10Plus(Presence.ABSENT)), true),
        Arguments.of(
            "unspecified, not declared",
            withoutHdr10Plus,
            hevcCopy(hdr10Plus(Presence.UNSPECIFIED)),
            true));
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
            true));
  }

  // ADR 0049: a profile 5 or 10.0 copy matches through its Dolby Vision profile and level alone, so
  // the entry needs no compatible base. Whether a worker writes the copy's dvh1 or dav1 sample
  // entry
  // is for the transcode decision to check.
  @ParameterizedTest(name = "{0}")
  @MethodSource("baselessDolbyVisionCopies")
  @DisplayName(
      "Should accept a Dolby Vision copy without a compatible base only when the entry lists its"
          + " profile at or above its level")
  void
      shouldAcceptADolbyVisionCopyWithoutACompatibleBaseOnlyWhenTheEntryListsItsProfileAtOrAboveItsLevel(
          String scenario, VideoCapabilityEntry entry, VideoStreamCopy copy, boolean accepted) {
    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> baselessDolbyVisionCopies() {
    var profile5 =
        hevcCopy(
            declaredDolbyVision(
                CompatibleBase.NONE, configuration(5).baseLayerCompatibilityId(OptionalInt.of(0))));
    var profile100 =
        av1Copy(
            declaredDolbyVision(
                CompatibleBase.NONE,
                configuration(10).baseLayerCompatibilityId(OptionalInt.of(0))));
    return Stream.of(
        Arguments.of(
            "5 at level 6, entry listing profile 5 alone at level 6",
            baselessEntryListing(DolbyVisionProfile.PROFILE_5, 6),
            profile5,
            true),
        Arguments.of(
            "5 at level 6, entry listing profile 5 alone at level 5",
            baselessEntryListing(DolbyVisionProfile.PROFILE_5, 5),
            profile5,
            false),
        Arguments.of(
            "5 at level 6, entry listing every base, HDR10+ and every other HEVC profile",
            everyDynamicRangeEntryBut(ProbedCodecFamily.HEVC, DolbyVisionProfile.PROFILE_5),
            profile5,
            false),
        Arguments.of(
            "5 at level 6, SDR and PQ entry without Dolby Vision",
            hevcEntryBuilder()
                .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ))
                .build(),
            profile5,
            false),
        Arguments.of(
            "5 from its RPU header without a maximum frame rate, entry listing profile 5",
            baselessEntryListing(DolbyVisionProfile.PROFILE_5, 13),
            hevcCopy(dolbyVision(CompatibleBase.NONE, profile5RpuHeader())),
            false),
        Arguments.of(
            "10.0 at level 6, entry listing profile 10.0 alone at level 6",
            av1EntryBuilder()
                .compatibleBases(List.of())
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_10_0, 6)))
                .build(),
            profile100,
            true),
        Arguments.of(
            "10.0 at level 6, entry listing profile 10.0 alone at level 5",
            av1EntryBuilder()
                .compatibleBases(List.of())
                .dolbyVisionProfiles(
                    List.of(dolbyVisionCapability(DolbyVisionProfile.PROFILE_10_0, 5)))
                .build(),
            profile100,
            false),
        Arguments.of(
            "10.0 at level 6, entry listing every base, HDR10+ and every other AV1 profile",
            everyDynamicRangeEntryBut(ProbedCodecFamily.AV1, DolbyVisionProfile.PROFILE_10_0),
            profile100,
            false));
  }

  private static VideoCapabilityEntry everyDynamicRangeEntryBut(
      ProbedCodecFamily family, DolbyVisionProfile unlisted) {
    return videoEntryBuilderFor(family)
        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
        .dolbyVisionProfiles(
            Stream.of(DolbyVisionProfile.values())
                .filter(profile -> profile != unlisted)
                .map(profile -> dolbyVisionCapability(profile, 13))
                .toList())
        .hdr10Plus(true)
        .build();
  }

  private static List<DolbyVisionProfileCapability> everyProfileAtTheHighestLevel() {
    return Stream.of(DolbyVisionProfile.values())
        .map(profile -> dolbyVisionCapability(profile, 13))
        .toList();
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
            "8.2 from an RPU header that sets the resampling filter flag but disables residuals",
            hevcEntryBuilder().build(),
            hevcCopy(
                dolbyVision(
                    CompatibleBase.SDR,
                    rpuHeader(
                        DolbyVisionRpuHeader.builder()
                            .vdrRpuProfile(1)
                            .enhancementLayerSpatialResamplingFilter(true)
                            .disableResidual(true)
                            .vdrBitDepth(12)
                            .build()))),
            true));
  }

  // No entry can list profile 4, 7 or 10 on an SDR base, an unknown profile or Dolby Vision
  // without a level, so the entry under test lists every base, Dolby Vision profile and HDR10+.
  @ParameterizedTest(name = "{0}")
  @MethodSource("undeclarableDolbyVisionCopies")
  @DisplayName(
      "Should refuse a Dolby Vision copy whatever the entry declares when no entry can list its"
          + " profile or level")
  void shouldRefuseADolbyVisionCopyWhateverTheEntryDeclaresWhenNoEntryCanListItsProfileOrLevel(
      String scenario, VideoStreamCopy copy) {
    var everyDynamicRange = everyDynamicRangeEntryFor(copy.codecFamily());

    assertThat(everyDynamicRange.acceptsHlsCopy(copy)).isFalse();
  }

  static Stream<Arguments> undeclarableDolbyVisionCopies() {
    return Stream.of(
        Arguments.of(
            "7 on a PQ base",
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.PQ,
                    configuration(7).baseLayerCompatibilityId(OptionalInt.of(6))))),
        Arguments.of(
            "7 named by its RPU header on a PQ base",
            hevcCopy(dolbyVision(CompatibleBase.PQ, enhancementLayerRpuHeader()))),
        Arguments.of(
            "7 named by its RPU header on an SDR base",
            hevcCopy(dolbyVision(CompatibleBase.SDR, enhancementLayerRpuHeader()))),
        Arguments.of(
            "4 from its record on an SDR base",
            hevcCopy(
                declaredDolbyVision(
                    CompatibleBase.SDR,
                    configuration(4).baseLayerCompatibilityId(OptionalInt.of(2))))),
        Arguments.of(
            "10 on an SDR base",
            av1Copy(
                declaredDolbyVision(
                    CompatibleBase.SDR,
                    configuration(10).baseLayerCompatibilityId(OptionalInt.of(2))))),
        Arguments.of(
            "H.264 whose record names profile 8 on an SDR base",
            h264Copy(
                declaredDolbyVision(
                    CompatibleBase.SDR,
                    configuration(8).baseLayerCompatibilityId(OptionalInt.of(2))))),
        Arguments.of(
            "an RPU header that names no profile",
            hevcCopy(
                dolbyVision(
                    CompatibleBase.SDR,
                    rpuHeader(
                        DolbyVisionRpuHeader.builder().vdrRpuProfile(0).vdrBitDepth(10).build())))),
        Arguments.of(
            "unknown profile, with neither a record nor an RPU header",
            hevcCopy(
                new VideoDynamicRange(
                    CompatibleBase.SDR,
                    Presence.UNSPECIFIED,
                    new DolbyVision.Present(Optional.empty(), Presence.UNSPECIFIED)))),
        Arguments.of(
            "8.1 named by its RPU header, without a record level or a maximum frame rate",
            hevcCopy(dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader()))),
        Arguments.of(
            "10.1 named by its RPU header, without a record level or a maximum frame rate",
            av1Copy(dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader()))),
        Arguments.of(
            "8.1 named by its RPU header, with a maximum frame rate but no decoded size",
            VideoStreamCopyFixture.sdrHevcCopyBuilder()
                .decodedSize(Optional.empty())
                .maximumFrameRate(Optional.of(new Fraction(24, 1)))
                .dynamicRange(dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader()))
                .build()),
        Arguments.of(
            "8.1 named by its RPU header, at 3840×2160 with a maximum of 500 fps, above every level",
            filmRateHevcCopy(
                new PictureSize(3840, 2160),
                new Fraction(500, 1),
                dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader()))));
  }

  // Dolby's level table puts 3840×2160 at 24000/1001 fps at level 6, 3840×2160 at 60000/1001 fps
  // at level 9, 1920×1080 at 30 fps at level 4 and 1920×1080 at 60 fps at level 5. Each copy whose
  // maximum frame rate is above its frame rate takes the level of its maximum.
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopiesWithoutARecordLevel")
  @DisplayName(
      "Should derive the Dolby Vision level from the decoded size and maximum frame rate when the"
          + " record names none")
  void shouldDeriveTheDolbyVisionLevelFromTheDecodedSizeAndMaximumFrameRateWhenTheRecordNamesNone(
      String scenario, VideoCapabilityEntry entry, VideoStreamCopy copy, boolean accepted) {
    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> dolbyVisionCopiesWithoutARecordLevel() {
    var uhdFilm = new PictureSize(3840, 2160);
    var profile81FromItsRpuHeader =
        filmRateHevcCopy(
            uhdFilm,
            new Fraction(24_000, 1_001),
            dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader()));
    var profile81FromARecordWithoutALevel =
        filmRateHevcCopy(
            uhdFilm,
            new Fraction(60_000, 1_001),
            declaredDolbyVision(
                CompatibleBase.PQ,
                configuration(8)
                    .level(OptionalInt.empty())
                    .baseLayerCompatibilityId(OptionalInt.of(1))));
    var profile101FromItsRpuHeader =
        av1CopyWithA60FpsMaximum(dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader()));
    return Stream.of(
        Arguments.of(
            "8.1 from its RPU header at level 6, entry at level 6",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 6),
            profile81FromItsRpuHeader,
            true),
        Arguments.of(
            "8.1 from its RPU header at level 6, entry at level 5",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 5),
            profile81FromItsRpuHeader,
            false),
        Arguments.of(
            "8.1 from its RPU header at 24000/1001 fps with a 60 fps maximum, entry at level 9",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 9),
            filmRateHevcCopy(
                uhdFilm,
                new Fraction(60, 1),
                dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader())),
            true),
        Arguments.of(
            "8.1 from its RPU header at 24000/1001 fps with a 60 fps maximum, entry at level 8",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 8),
            filmRateHevcCopy(
                uhdFilm,
                new Fraction(60, 1),
                dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader())),
            false),
        Arguments.of(
            "8.1 from a record without a level at level 9, entry at level 9",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 9),
            profile81FromARecordWithoutALevel,
            true),
        Arguments.of(
            "8.1 from a record without a level at level 9, entry at level 8",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 8),
            profile81FromARecordWithoutALevel,
            false),
        Arguments.of(
            "10.1 from its RPU header at level 5, entry at level 5",
            av1PqEntryListing(DolbyVisionProfile.PROFILE_10_1, 5),
            profile101FromItsRpuHeader,
            true),
        Arguments.of(
            "10.1 from its RPU header at level 5, entry at level 4",
            av1PqEntryListing(DolbyVisionProfile.PROFILE_10_1, 4),
            profile101FromItsRpuHeader,
            false));
  }

  // Dolby's level table puts 3840×2160 at 60 fps at level 9 and 1920×1080 at 24 fps at level 3.
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopiesWithARecordLevel")
  @DisplayName(
      "Should judge a Dolby Vision copy at its record level when its size and maximum frame rate"
          + " give another level")
  void shouldJudgeADolbyVisionCopyAtItsRecordLevelWhenItsSizeAndMaximumFrameRateGiveAnotherLevel(
      String scenario, VideoStreamCopy copy, boolean accepted) {
    var entryAtLevel6 = pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 6);

    assertThat(entryAtLevel6.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> dolbyVisionCopiesWithARecordLevel() {
    return Stream.of(
        Arguments.of(
            "record level 6 at 3840×2160 with a maximum of 60 fps",
            filmRateHevcCopy(
                new PictureSize(3840, 2160), new Fraction(60, 1), profile81AtRecordLevel(6)),
            true),
        Arguments.of(
            "record level 7 at 1920×1080 with a maximum of 24 fps",
            filmRateHevcCopy(
                new PictureSize(1920, 1080), new Fraction(24, 1), profile81AtRecordLevel(7)),
            false));
  }

  // Dolby's level table runs from level 1 to 13, and puts 1920×1080 at 24 fps at level 3 and
  // 3840×2160 at 24 fps at level 6.
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopiesWithARecordLevelOutsideTheTable")
  @DisplayName(
      "Should derive the Dolby Vision level when the record names a level outside the level table")
  void shouldDeriveTheDolbyVisionLevelWhenTheRecordNamesALevelOutsideTheLevelTable(
      String scenario, VideoCapabilityEntry entry, VideoStreamCopy copy, boolean accepted) {
    assertThat(entry.acceptsHlsCopy(copy)).isEqualTo(accepted);
  }

  static Stream<Arguments> dolbyVisionCopiesWithARecordLevelOutsideTheTable() {
    var level0At1080p24 =
        filmRateHevcCopy(
            new PictureSize(1920, 1080), new Fraction(24, 1), profile81AtRecordLevel(0));
    return Stream.of(
        Arguments.of(
            "record level 0 at level 3, entry at level 3",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 3),
            level0At1080p24,
            true),
        Arguments.of(
            "record level 0 at level 3, entry at level 2",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 2),
            level0At1080p24,
            false),
        Arguments.of(
            "record level 14 at level 6, entry at level 13",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 13),
            filmRateHevcCopy(
                new PictureSize(3840, 2160), new Fraction(24, 1), profile81AtRecordLevel(14)),
            true),
        Arguments.of(
            "record level 0 without a maximum frame rate, entry at level 13",
            pqEntryListing(DolbyVisionProfile.PROFILE_8_1, 13),
            hevcCopy(profile81AtRecordLevel(0)),
            false));
  }

  // Until streamarr/streamarr-server#652, no probe outcome stores a maximum frame rate, and the
  // copy's own frame rate is no maximum.
  @Test
  @DisplayName(
      "Should refuse a Dolby Vision copy without a record level when its probe outcome stores no"
          + " maximum frame rate")
  void
      shouldRefuseADolbyVisionCopyWithoutARecordLevelWhenItsProbeOutcomeStoresNoMaximumFrameRate() {
    var source =
        MediaProbe.builder()
            .framerate(OptionalDouble.of(24))
            .width(3840)
            .height(2160)
            .videoCodec("hevc")
            .keyframes(new KeyframeKnowledge.NotReported())
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .codecFamily(Optional.of(ProbedCodecFamily.HEVC))
                        .bitDepth(OptionalInt.of(10))
                        .codecString(Optional.of("hvc1.2.4.L150.B0"))
                        .videoProperties(
                            Optional.of(
                                codedAs(
                                    VideoCodecProfile.HEVC_MAIN_10,
                                    Optional.of(VideoCodecTier.MAIN))))
                        .frameRateRule(
                            VideoPlaybackFactsFixture.declaredRateRule(new Fraction(24, 1)))
                        .dynamicRange(dolbyVision(CompatibleBase.PQ, singleLayerRpuHeader()))
                        .build()))
            .build();
    var copy = VideoStreamCopy.of(source).orElseThrow();
    var everyDynamicRange = everyDynamicRangeEntryFor(ProbedCodecFamily.HEVC);

    assertThat(copy.frameRate()).hasValue(24);
    assertThat(everyDynamicRange.acceptsHlsCopy(copy)).isFalse();
  }

  private static VideoCapabilityEntry everyDynamicRangeEntryFor(ProbedCodecFamily family) {
    return videoEntryBuilderFor(family)
        .compatibleBases(List.of(CompatibleBase.SDR, CompatibleBase.PQ, CompatibleBase.HLG))
        .dolbyVisionProfiles(everyProfileAtTheHighestLevel())
        .hdr10Plus(true)
        .build();
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

  private static VideoCapabilityEntry av1PqEntryListing(DolbyVisionProfile profile, int maxLevel) {
    return av1EntryBuilder()
        .compatibleBases(List.of(CompatibleBase.PQ))
        .dolbyVisionProfiles(List.of(dolbyVisionCapability(profile, maxLevel)))
        .build();
  }

  private static VideoStreamCopy hevcCopy(VideoDynamicRange range) {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder().dynamicRange(range).build();
  }

  // A copy whose frames run at 24000/1001 fps, while a faster section may raise its maximum frame
  // rate.
  private static VideoStreamCopy filmRateHevcCopy(
      PictureSize decodedSize, Fraction maximumFrameRate, VideoDynamicRange range) {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder()
        .decodedSize(Optional.of(decodedSize))
        .frameRate(OptionalDouble.of(24_000.0 / 1_001))
        .maximumFrameRate(Optional.of(maximumFrameRate))
        .dynamicRange(range)
        .build();
  }

  private static VideoStreamCopy av1Copy(VideoDynamicRange range) {
    return av1CopyBuilder(range).build();
  }

  // A 1920×1080 AV1 copy whose frames run at 30 fps, as the fixture's HEVC copy does, and whose
  // faster sections run at 60 fps.
  private static VideoStreamCopy av1CopyWithA60FpsMaximum(VideoDynamicRange range) {
    return av1CopyBuilder(range).maximumFrameRate(Optional.of(new Fraction(60, 1))).build();
  }

  private static VideoStreamCopy.VideoStreamCopyBuilder av1CopyBuilder(VideoDynamicRange range) {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder()
        .codecFamily(ProbedCodecFamily.AV1)
        .videoProperties(
            Optional.of(codedAs(VideoCodecProfile.AV1_MAIN, Optional.of(VideoCodecTier.MAIN))))
        .dynamicRange(range);
  }

  private static VideoDynamicRange profile81AtRecordLevel(int level) {
    return declaredDolbyVision(
        CompatibleBase.PQ,
        configuration(8).level(OptionalInt.of(level)).baseLayerCompatibilityId(OptionalInt.of(1)));
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

  private static VideoDynamicRange hdr10Plus(Presence hdr10Plus) {
    return new VideoDynamicRange(CompatibleBase.PQ, hdr10Plus, new DolbyVision.Absent());
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

  // Without a record, an RPU header with vdr_rpu_profile 0 and a full-range base layer names
  // profile 5.
  private static DolbyVision.Present profile5RpuHeader() {
    return rpuHeader(
        DolbyVisionRpuHeader.builder()
            .vdrRpuProfile(0)
            .baseLayerFullRange(true)
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
