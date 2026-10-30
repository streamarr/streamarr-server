package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.av1EntryBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.h264EntryBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.EncodeCodecProfile;
import com.streamarr.server.domain.streaming.Fraction;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.PictureSize;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoCodecProfile;
import com.streamarr.server.domain.streaming.VideoCodecProfileCapability;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fixtures.VideoPlaybackFactsFixture;
import java.time.Duration;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Quality Ladder Service Tests")
class QualityLadderServiceTest {

  private final QualityLadderService service = new QualityLadderService();

  @Test
  @DisplayName("Should generate all tiers when source is 1080p")
  void shouldGenerateAllTiersWhenSourceIs1080p() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(4);
    assertThat(variants.get(0).label()).isEqualTo("1080p");
    assertThat(variants.get(1).label()).isEqualTo("720p");
    assertThat(variants.get(2).label()).isEqualTo("480p");
    assertThat(variants.get(3).label()).isEqualTo("360p");
  }

  @Test
  @DisplayName("Should offer every tier up to 2160p when the source and the entry allow 4K")
  void shouldOfferEveryTierUpTo2160pWhenTheSourceAndTheEntryAllow4K() {
    var probe = buildProbe(3840, 2160, 40_000_000L);

    var variants = generateVariants(probe, StreamingOptions.builder().build());

    assertThat(variants)
        .extracting(
            QualityVariant::label,
            QualityVariant::width,
            QualityVariant::height,
            QualityVariant::videoBitrate)
        .containsExactly(
            tuple("2160p", 3840, 2160, 20_000_000L),
            tuple("1440p", 2560, 1440, 12_000_000L),
            tuple("1080p", 1920, 1080, 5_000_000L),
            tuple("720p", 1280, 720, 3_000_000L),
            tuple("480p", 854, 480, 1_500_000L),
            tuple("360p", 640, 360, 800_000L));
  }

  @ParameterizedTest
  @CsvSource({"AV1_MAIN, 11600000, 8100000", "H264_HIGH, 20000000, 12000000"})
  @DisplayName(
      "Should give each tier its nominal bitrate for the codec family that the encode uses")
  void shouldGiveEachTierItsNominalBitrateForTheCodecFamilyThatTheEncodeUses(
      EncodeCodecProfile codecProfile, long bitrate2160p, long bitrate1440p) {
    var probe = buildProbe(3840, 2160, 40_000_000L);
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(av1EntryBuilder().build(), h264EntryBuilder().build()))
            .build();

    var variants =
        service.generateVariants(
            ladderRequest(probe, StreamingOptions.builder().build())
                .capabilityDeclaration(declaration)
                .codecProfile(codecProfile)
                .build());

    assertThat(variants)
        .extracting(QualityVariant::label, QualityVariant::videoBitrate)
        .containsExactly(
            tuple("2160p", bitrate2160p),
            tuple("1440p", bitrate1440p),
            tuple("1080p", 5_000_000L),
            tuple("720p", 3_000_000L),
            tuple("480p", 1_500_000L),
            tuple("360p", 800_000L));
  }

  // The worker keeps each side even, so the 720p and 360p pictures stop a few pixels short of their
  // box's width. The next larger picture would not fit the box, so each still reaches it.
  @Test
  @DisplayName(
      "Should fit a wide film to each tier's box when the film is shorter than the tier's height")
  void shouldFitAWideFilmToEachTiersBoxWhenTheFilmIsShorterThanTheTiersHeight() {
    var probe = buildProbe(1920, 800, 8_000_000L);

    var variants = generateVariants(probe, StreamingOptions.builder().build());

    assertThat(variants)
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactly(
            tuple("1080p", 1920, 800),
            tuple("720p", 1276, 532),
            tuple("480p", 854, 356),
            tuple("360p", 638, 266));
  }

  @Test
  @DisplayName("Should cap at 720p when source is below 1080p")
  void shouldCapAt720pWhenSourceIsBelow1080p() {
    var probe = buildProbe(1280, 720, 4_000_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(3);
    assertThat(variants.get(0).label()).isEqualTo("720p");
    assertThat(variants.get(1).label()).isEqualTo("480p");
    assertThat(variants.get(2).label()).isEqualTo("360p");
  }

  @Test
  @DisplayName("Should filter tiers when client specifies max height")
  void shouldFilterTiersWhenClientSpecifiesMaxHeight() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxHeight(480).build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(2);
    assertThat(variants.get(0).label()).isEqualTo("480p");
    assertThat(variants.get(1).label()).isEqualTo("360p");
  }

  @Test
  @DisplayName("Should size the only variant at the maximum height when it is below every tier")
  void shouldSizeTheOnlyVariantAtTheMaximumHeightWhenItIsBelowEveryTier() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxHeight(300).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(534, 300, "300p"));
  }

  @ParameterizedTest
  @CsvSource({
    "LOW_360P, 640, 360, 800000",
    "MEDIUM_480P, 854, 480, 1500000",
    "HIGH_720P, 1280, 720, 3000000",
    "FULL_HD_1080P, 1920, 1080, 5000000",
    "UHD_4K, 3840, 2160, 8000000",
    "ORIGINAL, 3840, 2160, 8000000"
  })
  @DisplayName(
      "Should offer only a default variant within the fixed quality's box when the quality is"
          + " fixed")
  void shouldOfferOnlyADefaultVariantWithinTheFixedQualitysBoxWhenTheQualityIsFixed(
      VideoQuality quality, int expectedWidth, int expectedHeight, long expectedBitrate) {
    var probe = buildProbe(3840, 2160, 8_000_000L);
    var options = StreamingOptions.builder().quality(quality).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(
            QualityVariant::width,
            QualityVariant::height,
            QualityVariant::videoBitrate,
            QualityVariant::label)
        .containsExactly(
            tuple(expectedWidth, expectedHeight, expectedBitrate, StreamSession.defaultVariant()));
  }

  @ParameterizedTest
  @CsvSource({"1080, 1920, FULL_HD_1080P, 1080, 1920", "1920, 1080, UHD_4K, 1920, 1080"})
  @DisplayName(
      "Should fit a fixed quality's box to the source's orientation without enlarging the source")
  void shouldFitAFixedQualitysBoxToTheSourcesOrientationWithoutEnlargingTheSource(
      int sourceWidth,
      int sourceHeight,
      VideoQuality quality,
      int expectedWidth,
      int expectedHeight) {
    var probe = buildProbe(sourceWidth, sourceHeight, 8_000_000L);
    var options = StreamingOptions.builder().quality(quality).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height)
        .containsExactly(tuple(expectedWidth, expectedHeight));
  }

  @Test
  @DisplayName(
      "Should encode a fixed quality within the viewer's maximum bitrate when the source's bitrate"
          + " is higher")
  void shouldEncodeAFixedQualityWithinTheViewersMaximumBitrateWhenTheSourcesBitrateIsHigher() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options =
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).maxBitrate(1_000_000).build();

    var variants = generateVariants(probe, options);

    assertThat(variants).extracting(QualityVariant::videoBitrate).containsExactly(1_000_000L);
  }

  @ParameterizedTest
  @CsvSource({
    "3840, 2160, 20000000",
    "2560, 1440, 12000000",
    "1920, 1080, 5000000",
    "1280, 720, 3000000",
    "1000, 562, 3000000",
    "854, 480, 1500000",
    "320, 180, 800000"
  })
  @DisplayName(
      "Should encode at the nominal bitrate of the smallest tier whose box holds the picture when"
          + " the probe names no source bitrate")
  void
      shouldEncodeAtTheNominalBitrateOfTheSmallestTierWhoseBoxHoldsThePictureWhenTheProbeNamesNoSourceBitrate(
          int width, int height, long expectedBitrate) {
    var probe = buildProbe(width, height, 0L);
    var options = StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build();

    var variants = generateVariants(probe, options);

    assertThat(variants).extracting(QualityVariant::videoBitrate).containsExactly(expectedBitrate);
  }

  @ParameterizedTest
  @CsvSource({
    "LOW_360P, 800000",
    "HIGH_720P, 3000000",
    "FULL_HD_1080P, 5000000",
    "UHD_4K, 20000000"
  })
  @DisplayName(
      "Should encode a fixed quality at its tier's nominal bitrate when the probe names no source"
          + " bitrate")
  void shouldEncodeAFixedQualityAtItsTiersNominalBitrateWhenTheProbeNamesNoSourceBitrate(
      VideoQuality quality, long expectedBitrate) {
    var probe = buildProbe(1920, 1080, 0L);
    var options = StreamingOptions.builder().quality(quality).build();

    var variants = generateVariants(probe, options);

    assertThat(variants).extracting(QualityVariant::videoBitrate).containsExactly(expectedBitrate);
  }

  @ParameterizedTest
  @CsvSource({"20000000, 5000000", "2000000, 2000000"})
  @DisplayName(
      "Should encode at the lower of the nominal bitrate and the viewer's maximum when the probe"
          + " names no source bitrate")
  void
      shouldEncodeAtTheLowerOfTheNominalBitrateAndTheViewersMaximumWhenTheProbeNamesNoSourceBitrate(
          int maxBitrate, long expectedBitrate) {
    var probe = buildProbe(1920, 1080, 0L);
    var options =
        StreamingOptions.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .maxBitrate(maxBitrate)
            .build();

    var variants = generateVariants(probe, options);

    assertThat(variants).extracting(QualityVariant::videoBitrate).containsExactly(expectedBitrate);
  }

  @Test
  @DisplayName(
      "Should cap the nominal bitrate at the entry's codec level when the probe names no source"
          + " bitrate")
  void shouldCapTheNominalBitrateAtTheEntrysCodecLevelWhenTheProbeNamesNoSourceBitrate() {
    var probe = buildProbe(1920, 1080, 0L);
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();
    var levelTwelveOnly =
        defaultDeclarationBuilder()
            .video(
                List.of(
                    h264EntryBuilder()
                        .codecProfiles(
                            List.of(
                                VideoCodecProfileCapability.builder()
                                    .profile(VideoCodecProfile.H264_HIGH)
                                    .maxLevel(12)
                                    .build()))
                        .build()))
            .build();

    var variants =
        service.generateVariants(
            ladderRequest(probe, options).capabilityDeclaration(levelTwelveOnly).build());

    // Level 1.2 allows a 480 kb/s High profile rate, below the 360p tier's 800 kb/s.
    assertThat(variants).extracting(QualityVariant::videoBitrate).containsExactly(480_000L);
  }

  @Test
  @DisplayName(
      "Should skip every tier wider than the viewer's maximum width when the viewer sets one")
  void shouldSkipEveryTierWiderThanTheViewersMaximumWidthWhenTheViewerSetsOne() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxWidth(1000).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height)
        .containsExactly(tuple(854, 480), tuple(640, 360));
  }

  @Test
  @DisplayName(
      "Should size the only variant within the viewer's maximum width when every tier is wider")
  void shouldSizeTheOnlyVariantWithinTheViewersMaximumWidthWhenEveryTierIsWider() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxWidth(500).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(498, 280, "280p"));
  }

  @Test
  @DisplayName(
      "Should encode the only variant within the viewer's maximum bitrate when the viewer's limits"
          + " leave no tier")
  void shouldEncodeTheOnlyVariantWithinTheViewersMaximumBitrateWhenTheViewersLimitsLeaveNoTier() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxWidth(500).maxBitrate(1_000_000).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::videoBitrate)
        .containsExactly(tuple(498, 280, 1_000_000L));
  }

  @Test
  @DisplayName(
      "Should keep the smaller tier's bitrate when an entry shrinks a larger tier to the smaller"
          + " tier's size")
  void shouldKeepTheSmallerTiersBitrateWhenAnEntryShrinksALargerTierToTheSmallerTiersSize() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1280).maxHeight(720).build()))
            .build();

    var variants =
        service.generateVariants(
            ladderRequest(probe, StreamingOptions.builder().build())
                .capabilityDeclaration(declaration)
                .build());

    assertThat(variants)
        .extracting(QualityVariant::label, QualityVariant::height, QualityVariant::videoBitrate)
        .containsExactly(
            tuple("720p", 720, 3_000_000L),
            tuple("480p", 480, 1_500_000L),
            tuple("360p", 360, 800_000L));
  }

  @Test
  @DisplayName(
      "Should label a variant by its shorter side when the entry shrinks it short of its tier's"
          + " box")
  void shouldLabelAVariantByItsShorterSideWhenTheEntryShrinksItShortOfItsTiersBox() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(1600).maxHeight(900).build()))
            .build();

    var variants =
        service.generateVariants(
            ladderRequest(probe, StreamingOptions.builder().build())
                .capabilityDeclaration(declaration)
                .build());

    assertThat(variants)
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactly(
            tuple("900p", 1600, 900),
            tuple("720p", 1280, 720),
            tuple("480p", 854, 480),
            tuple("360p", 640, 360));
  }

  @Test
  @DisplayName("Should return single variant when source resolution is low")
  void shouldReturnSingleVariantWhenSourceResolutionIsLow() {
    var probe = buildProbe(320, 180, 500_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(320);
    assertThat(variants.get(0).height()).isEqualTo(180);
  }

  @ParameterizedTest
  @CsvSource({"CHROMA_444, 316", "CHROMA_422, 314"})
  @DisplayName(
      "Should round the output height down when a cropped source is smaller than the 360p tier's"
          + " box")
  void shouldRoundTheOutputHeightDownWhenACroppedSourceIsSmallerThanThe360pTiersBox(
      ChromaSubsampling subsampling, int expectedWidth) {
    var probe =
        defaultProbeBuilder()
            .width(320)
            .height(240)
            .bitrate(500_000L)
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .chromaSubsampling(Optional.of(subsampling))
                        .containerCropping(
                            Optional.of(
                                ContainerCropping.builder()
                                    .top(1)
                                    .bottom(2)
                                    .left(1)
                                    .right(2)
                                    .build()))
                        .build()))
            .build();
    var options = StreamingOptions.builder().build();

    assertThat(generateVariants(probe, options))
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(expectedWidth, 236, "236p"));
  }

  // ADR 0050: a variant costs its video bitrate plus the stream session's audio bitrate.
  @ParameterizedTest
  @CsvSource({
    "0, 3100000, '720p, 480p, 360p'",
    "128000, 3100000, '480p, 360p'",
    "384000, 1800000, '360p'"
  })
  @DisplayName(
      "Should offer a tier only when its video bitrate plus the session's audio bitrate is within"
          + " the viewer's maximum bitrate")
  void shouldOfferATierOnlyWhenItsVideoPlusAudioBitrateIsWithinTheViewersMaximumBitrate(
      long audioBitrate, int maxBitrate, String expectedLabels) {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxBitrate(maxBitrate).build();

    var variants =
        service.generateVariants(ladderRequest(probe, options).audioBitrate(audioBitrate).build());

    assertThat(variants)
        .extracting(QualityVariant::label)
        .containsExactly(expectedLabels.split(", "));
  }

  @ParameterizedTest
  @CsvSource({
    "FULL_HD_1080P, 8000000, 128000, 2000000, 1920, 1080, 1872000",
    "FULL_HD_1080P, 8000000, 128000, 928000, 1920, 1080, 800000",
    "FULL_HD_1080P, 8000000, 128000, 927999, 640, 360, 800000",
    "FULL_HD_1080P, 8000000, 128000, 700000, 640, 360, 700000",
    "FULL_HD_1080P, 8000000, 128000, 128000, 640, 360, 128000",
    "FULL_HD_1080P, 8000000, 384000, 100000, 640, 360, 100000",
    "FULL_HD_1080P, 300000, 128000, 500000, 640, 360, 300000",
    "FULL_HD_1080P, 0, 128000, 500000, 640, 360, 500000",
    "AUTO, 8000000, 128000, 500000, 640, 360, 500000",
    "AUTO, 300000, 128000, 500000, 640, 360, 300000"
  })
  @DisplayName(
      "Should bound the single variant by the maximum bitrate less the audio, or by the 360p box"
          + " and the 360p tier's bitrate, when the viewer sets a maximum bitrate")
  void
      shouldBoundTheSingleVariantByTheMaximumBitrateLessTheAudioOrByThe360pBoxWhenTheViewerSetsAMaximumBitrate(
          VideoQuality quality,
          long sourceBitrate,
          long audioBitrate,
          int maxBitrate,
          int expectedWidth,
          int expectedHeight,
          long expectedVideoBitrate) {
    var probe = buildProbe(1920, 1080, sourceBitrate);
    var options = StreamingOptions.builder().quality(quality).maxBitrate(maxBitrate).build();

    var variants =
        service.generateVariants(ladderRequest(probe, options).audioBitrate(audioBitrate).build());

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::videoBitrate)
        .containsExactly(tuple(expectedWidth, expectedHeight, expectedVideoBitrate));
  }

  @Test
  @DisplayName("Should filter tiers when client specifies max bitrate")
  void shouldFilterTiersWhenClientSpecifiesMaxBitrate() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxBitrate(2_000_000).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.videoBitrate()).isLessThanOrEqualTo(2_000_000));
  }

  @Test
  @DisplayName("Should generate correct bitrates when source is 1080p")
  void shouldGenerateCorrectBitratesWhenSourceIs1080p() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants.get(0).videoBitrate()).isEqualTo(5_000_000L);
    assertThat(variants.get(1).videoBitrate()).isEqualTo(3_000_000L);
    assertThat(variants.get(2).videoBitrate()).isEqualTo(1_500_000L);
    assertThat(variants.get(3).videoBitrate()).isEqualTo(800_000L);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("aspectRatioVariants")
  @DisplayName(
      "Should compute aspect-ratio-correct dimensions when generating variants for each quality tier")
  void shouldComputeAspectRatioCorrectDimensionsWhenGeneratingVariantsForEachQualityTier(
      String scenario, PictureSize source, List<PictureSize> expected) {
    var probe = buildProbe(source.width(), source.height(), 8_000_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(variant -> new PictureSize(variant.width(), variant.height()))
        .containsExactlyElementsOf(expected);
  }

  // FFmpeg's scale=-2:<height> divides the height times the aspect ratio by 2, rounds half away
  // from zero, and doubles the result. FFmpeg 8.0.1 wrote the rounded widths from synthetic frames.
  static Stream<Arguments> aspectRatioVariants() {
    return Stream.of(
        Arguments.of(
            "16:9 source",
            new PictureSize(1920, 1080),
            List.of(
                new PictureSize(1920, 1080),
                new PictureSize(1280, 720),
                new PictureSize(854, 480),
                new PictureSize(640, 360))),
        Arguments.of(
            "4:3 source",
            new PictureSize(1440, 1080),
            List.of(
                new PictureSize(1440, 1080),
                new PictureSize(960, 720),
                new PictureSize(640, 480),
                new PictureSize(480, 360))),
        Arguments.of(
            "21:9 ultrawide source",
            new PictureSize(2560, 1080),
            List.of(
                new PictureSize(2560, 1080),
                new PictureSize(1920, 810),
                new PictureSize(1280, 540),
                new PictureSize(854, 360),
                new PictureSize(640, 270))),
        Arguments.of(
            "1:1 square source",
            new PictureSize(1080, 1080),
            List.of(
                new PictureSize(1080, 1080),
                new PictureSize(720, 720),
                new PictureSize(480, 480),
                new PictureSize(360, 360))),
        Arguments.of(
            "9:16 portrait source",
            new PictureSize(1080, 1920),
            List.of(
                new PictureSize(1080, 1920),
                new PictureSize(720, 1280),
                new PictureSize(480, 854),
                new PictureSize(360, 640))),
        Arguments.of(
            "4:3 at 720p",
            new PictureSize(960, 720),
            List.of(
                new PictureSize(960, 720), new PictureSize(640, 480), new PictureSize(480, 360))));
  }

  @Test
  @DisplayName(
      "Should offer every tier up to 2160p when the source is larger than every tier's box")
  void shouldOfferEveryTierUpTo2160pWhenTheSourceIsLargerThanEveryTiersBox() {
    var probe = buildProbe(7680, 4320, 80_000_000L);

    var variants = generateVariants(probe, StreamingOptions.builder().build());

    assertThat(variants)
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactly(
            tuple("2160p", 3840, 2160),
            tuple("1440p", 2560, 1440),
            tuple("1080p", 1920, 1080),
            tuple("720p", 1280, 720),
            tuple("480p", 854, 480),
            tuple("360p", 640, 360));
  }

  @Test
  @DisplayName("Should turn each tier's box with the picture when a phone recording is upright")
  void shouldTurnEachTiersBoxWithThePictureWhenAPhoneRecordingIsUpright() {
    var probe =
        buildProbeBuilder(1920, 1080)
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .rotationDegrees(OptionalInt.of(-90))
                        .build()))
            .build();
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactly(
            tuple("1080p", 1080, 1920),
            tuple("720p", 720, 1280),
            tuple("480p", 480, 854),
            tuple("360p", 360, 640));
  }

  @Test
  @DisplayName("Should size the variants from the cropped picture when the container crops it")
  void shouldSizeTheVariantsFromTheCroppedPictureWhenTheContainerCropsIt() {
    var probe =
        buildProbeBuilder(1920, 1080)
            .videoPlaybackFacts(
                Optional.of(
                    VideoPlaybackFactsFixture.sdrH264FactsBuilder()
                        .containerCropping(
                            Optional.of(ContainerCropping.builder().top(140).bottom(140).build()))
                        .build()))
            .build();
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height)
        .containsExactly(tuple(1920, 800), tuple(1276, 532), tuple(854, 356), tuple(638, 266));
  }

  @Test
  @DisplayName(
      "Should align width to even number when the source is smaller than the 360p tier's box")
  void shouldAlignWidthToEvenNumberWhenTheSourceIsSmallerThanThe360pTiersBox() {
    var probe = buildProbe(319, 179, 500_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(318);
    assertThat(variants.get(0).height()).isEqualTo(178);
    assertThat(variants.get(0).label()).isEqualTo("178p");
  }

  @ParameterizedTest
  @CsvSource({
    "640, 360, 360p",
    "360, 640, 360p",
    "638, 360, 360p",
    "320, 180, 180p",
    "180, 320, 180p",
    "1920, 800, 1080p",
    "2560, 1072, 1440p"
  })
  @DisplayName(
      "Should label the variant by the tier whose box it reaches, or else by its shorter side, when"
          + " the source is the variant's size")
  void
      shouldLabelTheVariantByTheTierWhoseBoxItReachesOrElseByItsShorterSideWhenTheSourceIsTheVariantsSize(
          int width, int height, String expectedLabel) {
    var probe = buildProbe(width, height, 8_000_000L);

    var variants = generateVariants(probe, StreamingOptions.builder().build());

    assertThat(variants.getFirst())
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(width, height, expectedLabel);
  }

  @Test
  @DisplayName(
      "Should label the only variant by its shorter side when the viewer's maximum width bounds a"
          + " portrait picture")
  void shouldLabelTheOnlyVariantByItsShorterSideWhenTheViewersMaximumWidthBoundsAPortraitPicture() {
    var probe = buildProbe(1080, 1920, 8_000_000L);
    var options = StreamingOptions.builder().maxWidth(300).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(300, 534, "300p"));
  }

  @Test
  @DisplayName(
      "Should drop a variant labelled by its shorter side when another variant's tier has the same"
          + " label")
  void shouldDropAVariantLabelledByItsShorterSideWhenAnotherVariantsTierHasTheSameLabel() {
    var probe = buildProbe(3996, 2160, 40_000_000L);
    var declaration =
        defaultDeclarationBuilder()
            .video(List.of(h264EntryBuilder().maxWidth(2048).maxHeight(1080).build()))
            .build();

    var variants =
        service.generateVariants(
            ladderRequest(probe, StreamingOptions.builder().build())
                .capabilityDeclaration(declaration)
                .build());

    assertThat(variants)
        .extracting(QualityVariant::label, QualityVariant::width, QualityVariant::height)
        .containsExactly(
            tuple("1080p", 1920, 1038),
            tuple("720p", 1280, 692),
            tuple("480p", 854, 462),
            tuple("360p", 640, 346));
  }

  private List<QualityVariant> generateVariants(MediaProbe probe, StreamingOptions options) {
    return service.generateVariants(ladderRequest(probe, options).build());
  }

  // An H.264 ladder at 24 fps for a player whose only entry plays up to 3840×2160 at 60 fps.
  private static QualityLadderRequest.QualityLadderRequestBuilder ladderRequest(
      MediaProbe probe, StreamingOptions options) {
    return QualityLadderRequest.builder()
        .probe(probe)
        .viewerLimits(ViewerLimits.of(options))
        .capabilityDeclaration(defaultDeclarationBuilder().build())
        .codecProfile(EncodeCodecProfile.H264_HIGH)
        .frameRate(new Fraction(24, 1));
  }

  private MediaProbe buildProbe(int width, int height, long bitrate) {
    return buildProbeBuilder(width, height).bitrate(bitrate).build();
  }

  private static MediaProbe.MediaProbeBuilder buildProbeBuilder(int width, int height) {
    return MediaProbe.builder()
        .keyframes(new KeyframeKnowledge.NotReported())
        .duration(Duration.ofMinutes(120))
        .framerate(OptionalDouble.of(24.0))
        .width(width)
        .height(height)
        .videoCodec("h264")
        .audioCodec("aac")
        .bitrate(8_000_000L);
  }
}
