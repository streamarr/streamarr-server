package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.VideoStreamCopyFixture;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR 0049's rule for an HLS stream copy: one video capability entry must accept every property of
 * the copy, and a probe version 5 outcome's unknown values fit as the ADR decides. The entry under
 * test plays HEVC Main up to Main tier level 5.1 and HEVC Main 10 up to High tier level 4.1, at up
 * to 10 bits, a 1920×1080 picture in either orientation and 30 fps.
 */
@Tag("UnitTest")
@DisplayName("Video Capability Entry HLS Copy Tests")
class VideoCapabilityEntryHlsCopyTest {

  private static final Map<VideoCodecProfile, ProfileLimit> PROFILE_LIMITS =
      Map.of(
          VideoCodecProfile.HEVC_MAIN, new ProfileLimit(51, VideoCodecTier.MAIN),
          VideoCodecProfile.HEVC_MAIN_10, new ProfileLimit(41, VideoCodecTier.HIGH));
  private static final int MAX_BIT_DEPTH = 10;
  private static final PictureSize MAX_SIZE = new PictureSize(1920, 1080);
  private static final double MAX_FRAME_RATE = 30;
  // A measured frame rate fits up to 0.5 % above the maximum.
  private static final double MAX_MEASURED_FRAME_RATE = 30.15;

  private static final VideoCapabilityEntry ENTRY = entryBuilder().build();

  private static VideoCapabilityEntry.VideoCapabilityEntryBuilder entryBuilder() {
    return hevcEntryBuilder()
        .codecProfiles(
            PROFILE_LIMITS.entrySet().stream()
                .map(
                    limit ->
                        VideoCodecProfileCapability.builder()
                            .profile(limit.getKey())
                            .maxLevel(limit.getValue().maxLevel())
                            .maxTier(Optional.of(limit.getValue().maxTier()))
                            .build())
                .toList())
        .maxBitDepth(MAX_BIT_DEPTH)
        .maxWidth(MAX_SIZE.width())
        .maxHeight(MAX_SIZE.height())
        .maxFrameRate(MAX_FRAME_RATE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyCombination")
  @DisplayName("Should accept a copy only when the entry accepts every property of the copy")
  void shouldAcceptACopyOnlyWhenTheEntryAcceptsEveryPropertyOfTheCopy(CopyCase copyCase) {
    assertThat(ENTRY.acceptsHlsCopy(copyCase.copy())).isEqualTo(copyCase.fits());
  }

  static Stream<CopyCase> everyCombination() {
    return codings()
        .map(VideoCapabilityEntryHlsCopyTest::withinTheEntryCodedAs)
        .flatMap(VideoCapabilityEntryHlsCopyTest::atEveryBitDepth)
        .flatMap(VideoCapabilityEntryHlsCopyTest::atEverySize)
        .flatMap(VideoCapabilityEntryHlsCopyTest::atEveryFrameRate);
  }

  // A probe that cannot name the codec profile reports no level or tier either.
  private static Stream<Coding> codings() {
    var unknownProfile =
        Coding.builder()
            .profile(Optional.empty())
            .level(OptionalInt.empty())
            .tier(Optional.empty())
            .build();
    var knownProfiles =
        Stream.of(
                VideoCodecProfile.HEVC_MAIN,
                VideoCodecProfile.HEVC_MAIN_10,
                VideoCodecProfile.HEVC_FORMAT_RANGE_EXTENSIONS)
            .map(profile -> unknownProfile.toBuilder().profile(Optional.of(profile)).build())
            .flatMap(VideoCapabilityEntryHlsCopyTest::atEveryLevel)
            .flatMap(VideoCapabilityEntryHlsCopyTest::atEveryTier);
    return Stream.concat(Stream.of(unknownProfile), knownProfiles);
  }

  private static Stream<Coding> atEveryLevel(Coding coding) {
    return Stream.of(
            OptionalInt.of(41), OptionalInt.of(51), OptionalInt.of(52), OptionalInt.empty())
        .map(level -> coding.toBuilder().level(level).build());
  }

  private static Stream<Coding> atEveryTier(Coding coding) {
    return Stream.of(
            Optional.of(VideoCodecTier.MAIN),
            Optional.of(VideoCodecTier.HIGH),
            Optional.<VideoCodecTier>empty())
        .map(tier -> coding.toBuilder().tier(tier).build());
  }

  private static CopyCase withinTheEntryCodedAs(Coding coding) {
    return CopyCase.builder()
        .coding(coding)
        .bitDepth(OptionalInt.of(MAX_BIT_DEPTH))
        .decodedSize(Optional.of(MAX_SIZE))
        .frameRate(OptionalDouble.of(MAX_FRAME_RATE))
        .build();
  }

  private static Stream<CopyCase> atEveryBitDepth(CopyCase copyCase) {
    return Stream.of(OptionalInt.of(10), OptionalInt.of(11), OptionalInt.empty())
        .map(bitDepth -> copyCase.toBuilder().bitDepth(bitDepth).build());
  }

  private static Stream<CopyCase> atEverySize(CopyCase copyCase) {
    return Stream.of(
            Optional.of(MAX_SIZE),
            Optional.of(new PictureSize(1921, 1080)),
            Optional.of(new PictureSize(1920, 1081)),
            Optional.of(new PictureSize(1080, 1920)),
            Optional.of(new PictureSize(1081, 1920)),
            Optional.of(new PictureSize(1080, 1921)),
            Optional.<PictureSize>empty())
        .map(size -> copyCase.toBuilder().decodedSize(size).build());
  }

  private static Stream<CopyCase> atEveryFrameRate(CopyCase copyCase) {
    return Stream.of(
            OptionalDouble.of(30),
            OptionalDouble.of(30_000.0 / 1_001),
            OptionalDouble.of(30.01),
            OptionalDouble.of(30.14),
            OptionalDouble.of(30.15),
            OptionalDouble.of(30.16),
            OptionalDouble.empty())
        .map(frameRate -> copyCase.toBuilder().frameRate(frameRate).build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unknownValues")
  @DisplayName(
      "Should fit an unknown value only as ADR 0049 decides when probe version 5 reports it")
  void shouldFitAnUnknownValueOnlyAsAdr0049DecidesWhenProbeVersion5ReportsIt(
      String scenario, VideoStreamCopy copy, boolean fits) {
    assertThat(ENTRY.acceptsHlsCopy(copy)).isEqualTo(fits);
  }

  static Stream<Arguments> unknownValues() {
    var unknownLevelAndTier =
        VideoStreamProperties.builder()
            .codecProfile(Optional.of(VideoCodecProfile.HEVC_MAIN_10))
            .firstFramesRead(true)
            .build();
    return Stream.of(
        Arguments.of(
            "unknown codec profile",
            fittingCopy()
                .videoProperties(Optional.of(VideoStreamProperties.builder().build()))
                .build(),
            false),
        Arguments.of(
            "unknown bit depth", fittingCopy().bitDepth(OptionalInt.empty()).build(), false),
        Arguments.of(
            "unknown picture size", fittingCopy().decodedSize(Optional.empty()).build(), false),
        Arguments.of(
            "unknown level and tier at a known size and frame rate that fit",
            fittingCopy().videoProperties(Optional.of(unknownLevelAndTier)).build(),
            true),
        Arguments.of(
            "unknown level and tier above the frame-rate maximum",
            fittingCopy()
                .videoProperties(Optional.of(unknownLevelAndTier))
                .frameRate(OptionalDouble.of(60))
                .build(),
            false),
        Arguments.of(
            "unknown level and tier above the size maximum",
            fittingCopy()
                .videoProperties(Optional.of(unknownLevelAndTier))
                .decodedSize(Optional.of(new PictureSize(3840, 2160)))
                .build(),
            false),
        Arguments.of(
            "unknown level and tier with an unknown frame rate at a size that fits",
            fittingCopy()
                .videoProperties(Optional.of(unknownLevelAndTier))
                .frameRate(OptionalDouble.empty())
                .build(),
            true),
        Arguments.of(
            "unknown frame rate", fittingCopy().frameRate(OptionalDouble.empty()).build(), true));
  }

  @Test
  @DisplayName("Should accept a copy when every property is known and within the entry")
  void shouldAcceptACopyWhenEveryPropertyIsKnownAndWithinTheEntry() {
    assertThat(ENTRY.acceptsHlsCopy(fittingCopy().build())).isTrue();
  }

  @Test
  @DisplayName("Should refuse a copy when the entry does not apply to HLS")
  void shouldRefuseACopyWhenTheEntryDoesNotApplyToHls() {
    var directPlayOnly = entryBuilder().hls(false).build();

    assertThat(directPlayOnly.acceptsHlsCopy(fittingCopy().build())).isFalse();
  }

  @Test
  @DisplayName("Should refuse a copy when the entry names another codec family")
  void shouldRefuseACopyWhenTheEntryNamesAnotherCodecFamily() {
    assertThat(ENTRY.acceptsHlsCopy(fittingCopy().codecFamily(ProbedCodecFamily.AV1).build()))
        .isFalse();
    assertThat(ENTRY.acceptsHlsCopy(fittingCopy().codecFamily(ProbedCodecFamily.OTHER).build()))
        .isFalse();
  }

  @Test
  @DisplayName("Should refuse a level when only another codec profile of the entry allows it")
  void shouldRefuseALevelWhenOnlyAnotherCodecProfileOfTheEntryAllowsIt() {
    var main10AtLevel51 =
        fittingCopy()
            .videoProperties(
                Optional.of(properties(VideoCodecProfile.HEVC_MAIN_10, 51, VideoCodecTier.MAIN)))
            .build();
    var mainAtLevel51 =
        fittingCopy()
            .videoProperties(
                Optional.of(properties(VideoCodecProfile.HEVC_MAIN, 51, VideoCodecTier.MAIN)))
            .build();

    assertThat(ENTRY.acceptsHlsCopy(main10AtLevel51)).isFalse();
    assertThat(ENTRY.acceptsHlsCopy(mainAtLevel51)).isTrue();
  }

  @Test
  @DisplayName("Should refuse a tier when only another codec profile of the entry allows it")
  void shouldRefuseATierWhenOnlyAnotherCodecProfileOfTheEntryAllowsIt() {
    var mainHighTier =
        fittingCopy()
            .videoProperties(
                Optional.of(properties(VideoCodecProfile.HEVC_MAIN, 41, VideoCodecTier.HIGH)))
            .build();
    var main10HighTier =
        fittingCopy()
            .videoProperties(
                Optional.of(properties(VideoCodecProfile.HEVC_MAIN_10, 41, VideoCodecTier.HIGH)))
            .build();

    assertThat(ENTRY.acceptsHlsCopy(mainHighTier)).isFalse();
    assertThat(ENTRY.acceptsHlsCopy(main10HighTier)).isTrue();
  }

  @Test
  @DisplayName(
      "Should accept a probe version 4 copy when an HLS entry names its codec family whatever its"
          + " other properties")
  void shouldAcceptAProbeVersion4CopyWhenAnHlsEntryNamesItsCodecFamilyWhateverItsOtherProperties() {
    var version4Copy =
        fittingCopy()
            .videoProperties(Optional.empty())
            .bitDepth(OptionalInt.of(12))
            .decodedSize(Optional.of(new PictureSize(3840, 2160)))
            .frameRate(OptionalDouble.of(120))
            .build();

    assertThat(ENTRY.acceptsHlsCopy(version4Copy)).isTrue();
    assertThat(entryBuilder().hls(false).build().acceptsHlsCopy(version4Copy)).isFalse();
  }

  private static VideoStreamCopy.VideoStreamCopyBuilder fittingCopy() {
    return VideoStreamCopyFixture.sdrHevcCopyBuilder();
  }

  private static VideoStreamProperties properties(
      VideoCodecProfile profile, int level, VideoCodecTier tier) {
    return Coding.builder()
        .profile(Optional.of(profile))
        .level(OptionalInt.of(level))
        .tier(Optional.of(tier))
        .build()
        .properties();
  }

  record ProfileLimit(int maxLevel, @NonNull VideoCodecTier maxTier) {}

  @Builder(toBuilder = true)
  record Coding(
      @NonNull Optional<VideoCodecProfile> profile,
      @NonNull OptionalInt level,
      @NonNull Optional<VideoCodecTier> tier) {

    VideoStreamProperties properties() {
      return VideoStreamProperties.builder()
          .codecProfile(profile)
          .codecLevel(level)
          .codecTier(tier)
          .firstFramesRead(true)
          .build();
    }

    // A level or tier is bounded only by the maximums of the codec profile that it belongs to. An
    // unknown level or tier is bounded by the picture size and frame rate instead.
    boolean fits() {
      var limit = profile.map(PROFILE_LIMITS::get);
      if (limit.isEmpty()) {
        return false;
      }

      var levelFits = level.isEmpty() || level.getAsInt() <= limit.get().maxLevel();
      var tierFits =
          tier.isEmpty()
              || tier.get() == VideoCodecTier.MAIN
              || limit.get().maxTier() == VideoCodecTier.HIGH;
      return levelFits && tierFits;
    }
  }

  @Builder(toBuilder = true)
  record CopyCase(
      @NonNull Coding coding,
      @NonNull OptionalInt bitDepth,
      @NonNull Optional<PictureSize> decodedSize,
      @NonNull OptionalDouble frameRate) {

    VideoStreamCopy copy() {
      return fittingCopy()
          .videoProperties(Optional.of(coding.properties()))
          .bitDepth(bitDepth)
          .decodedSize(decodedSize)
          .frameRate(frameRate)
          .build();
    }

    boolean fits() {
      return coding.fits() && bitDepthFits() && sizeFits() && frameRateFits();
    }

    private boolean bitDepthFits() {
      return bitDepth.isPresent() && bitDepth.getAsInt() <= MAX_BIT_DEPTH;
    }

    // A picture fits in either orientation: its longer side within the longer maximum and its
    // shorter side within the shorter one.
    private boolean sizeFits() {
      return decodedSize
          .filter(
              size ->
                  Math.max(size.width(), size.height()) <= MAX_SIZE.width()
                      && Math.min(size.width(), size.height()) <= MAX_SIZE.height())
          .isPresent();
    }

    // An unknown frame rate fits, because a copy is the only path for a stream without one.
    private boolean frameRateFits() {
      return frameRate.isEmpty() || frameRate.getAsDouble() <= MAX_MEASURED_FRAME_RATE;
    }

    @Override
    public String toString() {
      return String.join(
          " ",
          coding.toString(),
          "bitDepth=" + bitDepth,
          "size=" + decodedSize,
          "frameRate=" + frameRate);
    }
  }
}
