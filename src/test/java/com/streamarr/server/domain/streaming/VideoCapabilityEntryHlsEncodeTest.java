package com.streamarr.server.domain.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.hevcEntryBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
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
      SizeCap sizeCap) {
    var maximum = sizeCap.entryMaximum();
    var entry = h264EntryBuilder().maxWidth(maximum.width()).maxHeight(maximum.height()).build();

    var output =
        entry.fitHlsEncode(
            fullHdRequest().source(sizeCap.source()).bounds(sizeCap.source()).build());

    assertThat(pictureOf(output)).contains(sizeCap.picture());
  }

  // A 16:9 and a 21:9 source under a 720p maximum, a 9:16 and a 9:20 source under a 1080p maximum
  // declared either way, a 16:9 source under a portrait 720p maximum, and a 16:9 source under an
  // odd maximum, whose height the worker keeps even.
  static Stream<SizeCap> pictureSizeCaps() {
    var hd = new PictureSize(1280, 720);
    var portraitFullHd = new PictureSize(1080, 1920);
    return Stream.of(
        SizeCap.builder().source(FULL_HD).entryMaximum(hd).picture(hd).build(),
        SizeCap.builder()
            .source(new PictureSize(2560, 1080))
            .entryMaximum(hd)
            .picture(new PictureSize(1280, 540))
            .build(),
        SizeCap.builder()
            .source(portraitFullHd)
            .entryMaximum(FULL_HD)
            .picture(portraitFullHd)
            .build(),
        SizeCap.builder()
            .source(portraitFullHd)
            .entryMaximum(portraitFullHd)
            .picture(portraitFullHd)
            .build(),
        SizeCap.builder()
            .source(FULL_HD)
            .entryMaximum(new PictureSize(720, 1280))
            .picture(hd)
            .build(),
        SizeCap.builder()
            .source(new PictureSize(1080, 2400))
            .entryMaximum(FULL_HD)
            .picture(new PictureSize(864, 1920))
            .build(),
        SizeCap.builder()
            .source(FULL_HD)
            .entryMaximum(new PictureSize(1000, 563))
            .picture(new PictureSize(1000, 562))
            .build());
  }

  @Builder
  record SizeCap(PictureSize source, PictureSize entryMaximum, PictureSize picture) {}

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("ratesAboveTheEntrysMaximum")
  @DisplayName(
      "Should divide the frame rate by the smallest whole number that fits when it is above the"
          + " entry's maximum")
  void shouldDivideTheFrameRateByTheSmallestWholeNumberThatFitsWhenItIsAboveTheEntrysMaximum(
      FrameRateFit fit) {
    assertThat(encodedFrameRate(fit)).contains(fit.encoded());
  }

  // ADR 0049: 60 becomes 30, 59.94 becomes 29.97 and 50 becomes 25. The halved 60000/1001 fits a
  // declared 29.97 through the half-percent margin, and 20101/400 lies just past that margin above
  // 50, so it is halved.
  static Stream<FrameRateFit> ratesAboveTheEntrysMaximum() {
    return Stream.of(
        frameRateFit(new Fraction(60, 1), 30).encoded(new Fraction(30, 1)).build(),
        frameRateFit(new Fraction(60_000, 1_001), 30).encoded(new Fraction(30_000, 1_001)).build(),
        frameRateFit(new Fraction(50, 1), 30).encoded(new Fraction(25, 1)).build(),
        frameRateFit(new Fraction(60, 1), 25).encoded(new Fraction(20, 1)).build(),
        frameRateFit(new Fraction(60, 1), 20).encoded(new Fraction(20, 1)).build(),
        frameRateFit(new Fraction(60_000, 1_001), 29.97)
            .encoded(new Fraction(30_000, 1_001))
            .build(),
        frameRateFit(new Fraction(20_101, 400), 50).encoded(new Fraction(20_101, 800)).build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ratesWithinTheEntrysMaximum")
  @DisplayName(
      "Should keep the frame rate when it is at most half a percent above the entry's maximum")
  void shouldKeepTheFrameRateWhenItIsAtMostHalfAPercentAboveTheEntrysMaximum(FrameRateFit fit) {
    assertThat(encodedFrameRate(fit)).contains(fit.encoded());
  }

  // ADR 0049's margin lets a declared 59.94 accept the exact NTSC rate 60000/1001, which lies just
  // above it. 201/4 lies exactly half a percent above 50.
  static Stream<FrameRateFit> ratesWithinTheEntrysMaximum() {
    return Stream.of(
        keptRate(new Fraction(24_000, 1_001), 30),
        keptRate(new Fraction(60, 1), 60),
        keptRate(new Fraction(60, 1), 59.94),
        keptRate(new Fraction(60_000, 1_001), 59.94),
        keptRate(new Fraction(201, 4), 50));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ratesDividedToTheFloor")
  @DisplayName(
      "Should divide the frame rate down to 10 fps when only that rate fits, with the same half"
          + " percent margin")
  void shouldDivideTheFrameRateDownTo10FpsWhenOnlyThatRateFitsWithTheSameHalfPercentMargin(
      FrameRateFit fit) {
    assertThat(encodedFrameRate(fit)).contains(fit.encoded());
  }

  // ADR 0049: division stops at 10 fps, and 29.97 divided by 3 still counts as 10 fps.
  static Stream<FrameRateFit> ratesDividedToTheFloor() {
    return Stream.of(
        frameRateFit(new Fraction(60, 1), 10).encoded(new Fraction(10, 1)).build(),
        frameRateFit(new Fraction(24, 1), 12).encoded(new Fraction(12, 1)).build(),
        frameRateFit(new Fraction(30, 1), 10).encoded(new Fraction(10, 1)).build(),
        frameRateFit(new Fraction(30_000, 1_001), 10).encoded(new Fraction(10_000, 1_001)).build());
  }

  static Stream<FrameRateFit> ratesThatFitOnlyBelow10Fps() {
    return Stream.of(
        frameRateFit(new Fraction(60, 1), 0.1).build(),
        frameRateFit(new Fraction(60, 1), 9).build(),
        frameRateFit(new Fraction(24, 1), 11).build(),
        frameRateFit(new Fraction(24, 1), 1e-9).build());
  }

  private static Optional<Fraction> encodedFrameRate(FrameRateFit fit) {
    var entry = h264EntryBuilder().maxFrameRate(fit.maxFrameRate()).build();

    return entry
        .fitHlsEncode(fullHdRequest().frameRate(fit.source()).build())
        .map(EncodedVideoOutput::frameRate);
  }

  private static FrameRateFit keptRate(Fraction rate, double maxFrameRate) {
    return frameRateFit(rate, maxFrameRate).encoded(rate).build();
  }

  private static FrameRateFit.FrameRateFitBuilder frameRateFit(
      Fraction source, double maxFrameRate) {
    return FrameRateFit.builder().source(source).maxFrameRate(maxFrameRate);
  }

  @Builder
  record FrameRateFit(Fraction source, double maxFrameRate, Fraction encoded) {}

  @ParameterizedTest(name = "{0}")
  @MethodSource("ratesThatFitOnlyBelow10Fps")
  @DisplayName("Should fit no encode when only a frame rate below 10 fps fits the entry")
  void shouldFitNoEncodeWhenOnlyAFrameRateBelow10FpsFitsTheEntry(FrameRateFit fit) {
    assertThat(encodedFrameRate(fit)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("levelLimitedPictures")
  @DisplayName(
      "Should step the size down until the codec level fits when the profile's maximum level is"
          + " below the size's level")
  void shouldStepTheSizeDownUntilTheCodecLevelFitsWhenTheProfilesMaximumLevelIsBelowTheSizesLevel(
      LevelLimitedPicture expected) {
    var entry = h264EntryBuilder().codecProfiles(List.of(h264High(31))).build();

    var output = entry.fitHlsEncode(fullHdRequest().frameRate(expected.frameRate()).build());

    assertThat(pictureOf(output)).contains(expected.picture());
    assertThat(output.map(VideoEncodeDecision::of).map(VideoEncodeDecision::level)).contains(31);
  }

  // H.264 Table A-1: level 3.1 holds 3,600 macroblocks per frame and 108,000 per second, so 720p
  // fits at 24 fps and 896×504, 1,792 macroblocks, is the largest 16:9 picture at 60 fps.
  static Stream<LevelLimitedPicture> levelLimitedPictures() {
    return Stream.of(
        LevelLimitedPicture.builder()
            .frameRate(new Fraction(24, 1))
            .picture(new PictureSize(1280, 720))
            .build(),
        LevelLimitedPicture.builder()
            .frameRate(new Fraction(60, 1))
            .picture(new PictureSize(896, 504))
            .build());
  }

  @Builder
  record LevelLimitedPicture(Fraction frameRate, PictureSize picture) {}

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

  @Test
  @DisplayName(
      "Should cap an AV1 encode's bitrate at the Main tier limit of the entry's maximum level when"
          + " the requested bitrate needs a higher level")
  void
      shouldCapAnAv1EncodesBitrateAtTheMainTierLimitOfTheEntrysMaximumLevelWhenTheRequestedBitrateNeedsAHigherLevel() {
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
            fullHdRequest().codecProfile(EncodeCodecProfile.AV1_MAIN).bitrate(20_000_000L).build());

    // AV1 Annex A.3 allows level 4.0 12 Mb/s in the Main tier.
    assertThat(output.map(EncodedVideoOutput::bitrate)).contains(12_000_000L);
    assertThat(pictureOf(output)).contains(FULL_HD);
  }

  @Test
  @DisplayName(
      "Should cap an HEVC encode's bitrate at half the Main tier buffer of the entry's maximum level"
          + " when the requested bitrate needs a higher level")
  void
      shouldCapAnHevcEncodesBitrateAtHalfTheMainTierBufferOfTheEntrysMaximumLevelWhenTheRequestedBitrateNeedsAHigherLevel() {
    var entry =
        hevcEntryBuilder()
            .codecProfiles(
                List.of(
                    VideoCodecProfileCapability.builder()
                        .profile(VideoCodecProfile.HEVC_MAIN)
                        .maxLevel(40)
                        .maxTier(Optional.of(VideoCodecTier.MAIN))
                        .build()))
            .build();

    var output =
        entry.fitHlsEncode(
            fullHdRequest()
                .codecProfile(EncodeCodecProfile.HEVC_MAIN)
                .bitrate(20_000_000L)
                .build());

    // H.265 Tables A.8 and A.9 give level 4 a 12,000,000-bit buffer and 12 Mb/s in the Main tier.
    // The worker's buffer is twice the bitrate, so the buffer allows only 6 Mb/s.
    assertThat(output.map(EncodedVideoOutput::bitrate)).contains(6_000_000L);
    assertThat(pictureOf(output)).contains(FULL_HD);
    assertThat(output.map(VideoEncodeDecision::of).map(VideoEncodeDecision::level)).contains(40);
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
      "Should keep the faster frame rate at the smaller size when two entries trade size for frame"
          + " rate")
  void shouldKeepTheFasterFrameRateAtTheSmallerSizeWhenTwoEntriesTradeSizeForFrameRate() {
    var declaration = uhdAt30AndFullHdAt60Declaration();

    var output =
        declaration.fitHlsEncode(
            fullHdRequest().source(UHD).bounds(UHD).frameRate(new Fraction(60, 1)).build());

    assertThat(output)
        .hasValueSatisfying(
            fitted -> {
              assertThat(pictureOf(fitted)).isEqualTo(FULL_HD);
              assertThat(fitted.frameRate()).isEqualTo(new Fraction(60, 1));
            });
  }

  @Test
  @DisplayName(
      "Should keep the frame rate and shrink the picture when the entry's level allows the picture"
          + " only at a lower rate")
  void
      shouldKeepTheFrameRateAndShrinkThePictureWhenTheEntrysLevelAllowsThePictureOnlyAtALowerRate() {
    var entry = h264EntryBuilder().codecProfiles(List.of(h264High(41))).build();

    var output = entry.fitHlsEncode(fullHdRequest().frameRate(new Fraction(60, 1)).build());

    assertThat(output)
        .hasValueSatisfying(
            fitted -> {
              assertThat(pictureOf(fitted)).isEqualTo(new PictureSize(1358, 764));
              assertThat(fitted.frameRate()).isEqualTo(new Fraction(60, 1));
              assertThat(VideoEncodeDecision.of(fitted).level()).isLessThanOrEqualTo(41);
            });
  }

  @Test
  @DisplayName(
      "Should keep the higher bitrate when two entries accept the same picture at the same frame"
          + " rate")
  void shouldKeepTheHigherBitrateWhenTwoEntriesAcceptTheSamePictureAtTheSameFrameRate() {
    var declaration =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder().codecProfiles(List.of(h264High(40))).build(),
                    h264EntryBuilder().codecProfiles(List.of(h264High(51))).build()))
            .build();

    var output = declaration.fitHlsEncode(fullHdRequest().bitrate(20_000_000L).build());

    assertThat(output.map(EncodedVideoOutput::bitrate)).contains(20_000_000L);
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
