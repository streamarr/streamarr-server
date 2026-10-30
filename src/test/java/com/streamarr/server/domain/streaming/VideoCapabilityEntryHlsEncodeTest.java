package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Capability Entry HLS Encode Tests")
class VideoCapabilityEntryHlsEncodeTest {

  private static final PictureSize FULL_HD = new PictureSize(1920, 1080);
  private static final PictureSize UHD = new PictureSize(3840, 2160);
  private static final PictureSize UNBOUNDED =
      new PictureSize(Integer.MAX_VALUE, Integer.MAX_VALUE);

  @Test
  @DisplayName("Should keep the requested output when the entry accepts every property of it")
  void shouldKeepTheRequestedOutputWhenTheEntryAcceptsEveryPropertyOfIt() {
    var entry = h264EntryBuilder().build();

    var output = entry.fitHlsEncode(fullHdRequest().build());

    assertThat(output)
        .contains(
            EncodedVideoOutput.builder()
                .codecFamily("h264")
                .width(1920)
                .height(1080)
                .frameRate(new Fraction(24, 1))
                .bitrate(5_000_000L)
                .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("pictureSizeCaps")
  @DisplayName(
      "Should encode at the largest size the entry accepts when the source is larger, keeping its"
          + " aspect ratio")
  void shouldEncodeAtTheLargestSizeTheEntryAcceptsWhenTheSourceIsLargerKeepingItsAspectRatio(
      String scenario, PictureSize source, PictureSize entryMaximum, PictureSize expected) {
    var entry =
        h264EntryBuilder().maxWidth(entryMaximum.width()).maxHeight(entryMaximum.height()).build();

    var output = entry.fitHlsEncode(fullHdRequest().source(source).bounds(source).build());

    assertThat(pictureOf(output)).contains(expected);
  }

  static Stream<Arguments> pictureSizeCaps() {
    return Stream.of(
        Arguments.of(
            "16:9 source under a 720p maximum",
            FULL_HD,
            new PictureSize(1280, 720),
            new PictureSize(1280, 720)),
        Arguments.of(
            "21:9 source under a 720p maximum",
            new PictureSize(2560, 1080),
            new PictureSize(1280, 720),
            new PictureSize(1280, 540)),
        Arguments.of(
            "9:16 source under a 1080p maximum",
            new PictureSize(1080, 1920),
            FULL_HD,
            new PictureSize(608, 1080)),
        Arguments.of(
            "16:9 source under an odd 1000×563 maximum",
            FULL_HD,
            new PictureSize(1000, 563),
            new PictureSize(1000, 562)));
  }

  @Test
  @DisplayName(
      "Should refuse the encode when the entry's maximums shrink the source below the smallest"
          + " picture")
  void shouldRefuseTheEncodeWhenTheEntrysMaximumsShrinkTheSourceBelowTheSmallestPicture() {
    var entry = h264EntryBuilder().maxWidth(1280).maxHeight(720).build();
    var source = new PictureSize(4096, 2);

    var output = entry.fitHlsEncode(fullHdRequest().source(source).bounds(source).build());

    assertThat(output).isEmpty();
  }

  @Test
  @DisplayName("Should keep the variant within the request's bounds when the entry allows more")
  void shouldKeepTheVariantWithinTheRequestsBoundsWhenTheEntryAllowsMore() {
    var entry = h264EntryBuilder().build();

    var output = entry.fitHlsEncode(fullHdRequest().bounds(new PictureSize(1280, 1080)).build());

    assertThat(pictureOf(output)).contains(new PictureSize(1280, 720));
  }

  @ParameterizedTest
  @CsvSource({
    "60, 1, 30, 30, 1",
    "60000, 1001, 30, 30000, 1001",
    "50, 1, 30, 25, 1",
    "60, 1, 25, 20, 1",
    "60, 1, 20, 20, 1",
    "24000, 1001, 30, 24000, 1001",
    "60, 1, 60, 60, 1",
    // A rate fits up to half a percent above the maximum, so a declared 29.97 or 59.94 accepts the
    // exact NTSC rate 30000/1001 or 60000/1001.
    "60000, 1001, 29.97, 30000, 1001",
    "60000, 1001, 59.94, 60000, 1001",
    "60, 1, 59.94, 60, 1",
    "201, 4, 50, 201, 4",
    "20101, 400, 50, 20101, 800"
  })
  @DisplayName(
      "Should divide the frame rate by the smallest whole number that fits when it is above the"
          + " entry's maximum")
  void shouldDivideTheFrameRateByTheSmallestWholeNumberThatFitsWhenItIsAboveTheEntrysMaximum(
      int numerator,
      int denominator,
      double maxFrameRate,
      int expectedNumerator,
      int expectedDenominator) {
    var entry = h264EntryBuilder().maxFrameRate(maxFrameRate).build();

    var output =
        entry.fitHlsEncode(fullHdRequest().frameRate(new Fraction(numerator, denominator)).build());

    assertThat(output.map(EncodedVideoOutput::frameRate))
        .contains(new Fraction(expectedNumerator, expectedDenominator));
  }

  @Test
  @DisplayName("Should refuse the encode when no whole divisor keeps the rate a fraction of ints")
  void shouldRefuseTheEncodeWhenNoWholeDivisorKeepsTheRateAFractionOfInts() {
    var entry = h264EntryBuilder().maxFrameRate(1e-9).build();

    assertThat(entry.fitHlsEncode(fullHdRequest().build())).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"24, 1280, 720", "60, 896, 504"})
  @DisplayName(
      "Should step the size down until the codec level fits when the profile's maximum level is"
          + " below the size's level")
  void shouldStepTheSizeDownUntilTheCodecLevelFitsWhenTheProfilesMaximumLevelIsBelowTheSizesLevel(
      int frameRate, int expectedWidth, int expectedHeight) {
    var entry = h264EntryBuilder().codecProfiles(List.of(h264High(31))).build();

    var output = entry.fitHlsEncode(fullHdRequest().frameRate(new Fraction(frameRate, 1)).build());

    assertThat(pictureOf(output)).contains(new PictureSize(expectedWidth, expectedHeight));
    assertThat(output.map(VideoEncodeDecision::of).map(VideoEncodeDecision::level)).contains(31);
  }

  @Test
  @DisplayName(
      "Should cap the bitrate at the profile's maximum level when the requested bitrate needs a"
          + " higher level")
  void shouldCapTheBitrateAtTheProfilesMaximumLevelWhenTheRequestedBitrateNeedsAHigherLevel() {
    var entry = h264EntryBuilder().codecProfiles(List.of(h264High(40))).build();

    var output = entry.fitHlsEncode(fullHdRequest().bitrate(20_000_000L).build());

    // Level 4.0 allows a 25 Mb/s rate but a 31.25 Mb coded picture buffer, which the worker fills
    // with twice the bitrate.
    assertThat(output.map(EncodedVideoOutput::bitrate)).contains(15_625_000L);
    assertThat(pictureOf(output)).contains(FULL_HD);
  }

  @Test
  @DisplayName("Should refuse the encode when the profile's maximum level is below every level")
  void shouldRefuseTheEncodeWhenTheProfilesMaximumLevelIsBelowEveryLevel() {
    var entry = h264EntryBuilder().codecProfiles(List.of(h264High(9))).build();

    assertThat(entry.fitHlsEncode(fullHdRequest().build())).isEmpty();
  }

  @Test
  @DisplayName("Should fit AV1 Main in the Main tier when the AV1 entry's level allows it")
  void shouldFitAv1MainInTheMainTierWhenTheAv1EntrysLevelAllowsIt() {
    var entry =
        av1EntryBuilder()
            .codecProfiles(
                List.of(
                    VideoCodecProfileCapability.builder()
                        .profile(VideoCodecProfile.AV1_MAIN)
                        .maxLevel(40)
                        .maxTier(Optional.of(VideoCodecTier.MAIN))
                        .build()))
            .build();

    var output =
        entry.fitHlsEncode(
            fullHdRequest()
                .codecProfile(EncodeCodecProfile.AV1_MAIN)
                .frameRate(new Fraction(60, 1))
                .build());

    assertThat(pictureOf(output)).contains(new PictureSize(1448, 814));
    assertThat(output.map(VideoEncodeDecision::of))
        .hasValueSatisfying(
            decision -> {
              assertThat(decision.level()).isEqualTo(40);
              assertThat(decision.tier()).contains(VideoCodecTier.MAIN);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("entriesThatRefuseAnEncode")
  @DisplayName("Should refuse the encode when the entry refuses one of its fixed properties")
  void shouldRefuseTheEncodeWhenTheEntryRefusesOneOfItsFixedProperties(
      String scenario, VideoCapabilityEntry entry, EncodeCodecProfile codecProfile) {
    var output = entry.fitHlsEncode(fullHdRequest().codecProfile(codecProfile).build());

    assertThat(output).isEmpty();
  }

  static Stream<Arguments> entriesThatRefuseAnEncode() {
    return Stream.of(
        Arguments.of(
            "an entry without SDR",
            h264EntryBuilder().compatibleBases(List.of(CompatibleBase.PQ)).build(),
            EncodeCodecProfile.H264_HIGH),
        Arguments.of(
            "an entry below 8 bits",
            h264EntryBuilder().maxBitDepth(7).build(),
            EncodeCodecProfile.H264_HIGH),
        Arguments.of(
            "an entry that does not apply to HLS",
            h264EntryBuilder().hls(false).build(),
            EncodeCodecProfile.H264_HIGH),
        Arguments.of(
            "an entry without the encode's codec profile",
            h264EntryBuilder()
                .codecProfiles(
                    List.of(
                        VideoCodecProfileCapability.builder()
                            .profile(VideoCodecProfile.H264_MAIN)
                            .maxLevel(52)
                            .build()))
                .build(),
            EncodeCodecProfile.H264_HIGH),
        Arguments.of(
            "an entry of another codec family",
            h264EntryBuilder().build(),
            EncodeCodecProfile.AV1_MAIN));
  }

  @Test
  @DisplayName(
      "Should choose the largest size one entry accepts when two entries trade size for frame rate")
  void shouldChooseTheLargestSizeOneEntryAcceptsWhenTwoEntriesTradeSizeForFrameRate() {
    var declaration = uhdAt30AndFullHdAt60Declaration();

    var output =
        declaration.fitHlsEncode(
            fullHdRequest().source(UHD).bounds(UHD).frameRate(new Fraction(60, 1)).build());

    assertThat(output)
        .hasValueSatisfying(
            fitted -> {
              assertThat(pictureOf(fitted)).isEqualTo(UHD);
              assertThat(fitted.frameRate()).isEqualTo(new Fraction(30, 1));
            });
  }

  @Test
  @DisplayName(
      "Should keep the faster frame rate when two entries accept the same size at different rates")
  void shouldKeepTheFasterFrameRateWhenTwoEntriesAcceptTheSameSizeAtDifferentRates() {
    var declaration = uhdAt30AndFullHdAt60Declaration();

    var output = declaration.fitHlsEncode(fullHdRequest().frameRate(new Fraction(60, 1)).build());

    assertThat(output)
        .hasValueSatisfying(
            fitted -> {
              assertThat(pictureOf(fitted)).isEqualTo(FULL_HD);
              assertThat(fitted.frameRate()).isEqualTo(new Fraction(60, 1));
            });
  }

  @Test
  @DisplayName(
      "Should never take the size of one entry with the codec profile of another when neither"
          + " entry has both")
  void shouldNeverTakeTheSizeOfOneEntryWithTheCodecProfileOfAnotherWhenNeitherEntryHasBoth() {
    var mainOnlyUhd =
        h264EntryBuilder()
            .codecProfiles(
                List.of(
                    VideoCodecProfileCapability.builder()
                        .profile(VideoCodecProfile.H264_MAIN)
                        .maxLevel(52)
                        .build()))
            .build();
    var highAt720 = h264EntryBuilder().maxWidth(1280).maxHeight(720).build();
    var declaration = defaultDeclarationBuilder().video(List.of(mainOnlyUhd, highAt720)).build();

    var output = declaration.fitHlsEncode(fullHdRequest().build());

    assertThat(pictureOf(output)).contains(new PictureSize(1280, 720));
  }

  @Test
  @DisplayName("Should fit no encode when no entry of the declaration accepts one")
  void shouldFitNoEncodeWhenNoEntryOfTheDeclarationAcceptsOne() {
    var declaration = defaultDeclarationBuilder().build();

    var output =
        declaration.fitHlsEncode(fullHdRequest().codecProfile(EncodeCodecProfile.AV1_MAIN).build());

    assertThat(output).isEmpty();
  }

  // A player that plays H.264 at 3840×2160 up to 30 fps and at 1920×1080 up to 60 fps.
  private static ClientCapabilityDeclaration uhdAt30AndFullHdAt60Declaration() {
    return defaultDeclarationBuilder()
        .video(
            List.of(
                h264EntryBuilder().maxWidth(3840).maxHeight(2160).maxFrameRate(30).build(),
                h264EntryBuilder().maxWidth(1920).maxHeight(1080).maxFrameRate(60).build()))
        .build();
  }

  private static VideoCodecProfileCapability h264High(int maxLevel) {
    return VideoCodecProfileCapability.builder()
        .profile(VideoCodecProfile.H264_HIGH)
        .maxLevel(maxLevel)
        .build();
  }

  private static Optional<PictureSize> pictureOf(Optional<EncodedVideoOutput> output) {
    return output.map(VideoCapabilityEntryHlsEncodeTest::pictureOf);
  }

  private static PictureSize pictureOf(EncodedVideoOutput output) {
    return new PictureSize(output.width(), output.height());
  }

  // An H.264 High encode of a 1920×1080 source at 24 fps and 5 Mb/s, unbounded by the viewer.
  private static EncodedVariantRequest.EncodedVariantRequestBuilder fullHdRequest() {
    return EncodedVariantRequest.builder()
        .codecProfile(EncodeCodecProfile.H264_HIGH)
        .source(FULL_HD)
        .bounds(UNBOUNDED)
        .frameRate(new Fraction(24, 1))
        .bitrate(5_000_000L);
  }
}
