package com.streamarr.server.services.streaming;

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
import lombok.Builder;
import org.assertj.core.groups.Tuple;
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
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(4);
    assertThat(variants.get(0).label()).isEqualTo("1080p");
    assertThat(variants.get(1).label()).isEqualTo("720p");
    assertThat(variants.get(2).label()).isEqualTo("480p");
    assertThat(variants.get(3).label()).isEqualTo("360p");
  }

  // The worker keeps each side even, so the 720p and 360p pictures stop a few pixels short of their
  // box's width. The next larger picture would not fit the box, so each still reaches it.
  @Test
  @DisplayName(
      "Should fit a wide film to each tier's box when the film is shorter than the tier's height")
  void shouldFitAWideFilmToEachTiersBoxWhenTheFilmIsShorterThanTheTiersHeight() {
    var probe = buildProbeBuilder(1920, 800).bitrate(8_000_000L).build();

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
    var probe = buildProbeBuilder(1280, 720).bitrate(4_000_000L).build();
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
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
    var options = StreamingOptions.builder().maxHeight(480).build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(2);
    assertThat(variants.get(0).label()).isEqualTo("480p");
    assertThat(variants.get(1).label()).isEqualTo("360p");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixedQualityBoxes")
  @DisplayName(
      "Should offer only a default variant within the fixed quality's box when the quality is"
          + " fixed")
  void shouldOfferOnlyADefaultVariantWithinTheFixedQualitysBoxWhenTheQualityIsFixed(
      FixedQualityCase fixedQuality) {
    var probe = buildProbeBuilder(3840, 2160).bitrate(8_000_000L).build();
    var options = StreamingOptions.builder().quality(fixedQuality.quality()).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(
            tuple(
                fixedQuality.picture().width(),
                fixedQuality.picture().height(),
                StreamSession.defaultVariant()));
    assertThat(variants)
        .extracting(QualityVariant::videoBitrate)
        .containsExactly(fixedQuality.videoBitrate());
  }

  // ADR 0050's tier boxes. The 4K fixed quality's box is the 2160p tier's, and the source's 8 Mb/s
  // caps the 2160p tier's nominal bitrate.
  static Stream<FixedQualityCase> fixedQualityBoxes() {
    return Stream.of(
        FixedQualityCase.builder()
            .quality(VideoQuality.LOW_360P)
            .picture(new PictureSize(640, 360))
            .videoBitrate(800_000L)
            .build(),
        FixedQualityCase.builder()
            .quality(VideoQuality.MEDIUM_480P)
            .picture(new PictureSize(854, 480))
            .videoBitrate(1_500_000L)
            .build(),
        FixedQualityCase.builder()
            .quality(VideoQuality.HIGH_720P)
            .picture(new PictureSize(1280, 720))
            .videoBitrate(3_000_000L)
            .build(),
        FixedQualityCase.builder()
            .quality(VideoQuality.FULL_HD_1080P)
            .picture(new PictureSize(1920, 1080))
            .videoBitrate(5_000_000L)
            .build(),
        FixedQualityCase.builder()
            .quality(VideoQuality.UHD_4K)
            .picture(new PictureSize(3840, 2160))
            .videoBitrate(8_000_000L)
            .build());
  }

  @Builder
  record FixedQualityCase(VideoQuality quality, PictureSize picture, long videoBitrate) {}

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesWithinTheirFixedQualitysBox")
  @DisplayName(
      "Should keep the source's size when it fits the fixed quality's box turned to its"
          + " orientation")
  void shouldKeepTheSourcesSizeWhenItFitsTheFixedQualitysBoxTurnedToItsOrientation(
      SourceInBox sourceInBox) {
    var source = sourceInBox.source();
    var probe = buildProbeBuilder(source.width(), source.height()).bitrate(8_000_000L).build();
    var options = StreamingOptions.builder().quality(sourceInBox.quality()).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(variant -> new PictureSize(variant.width(), variant.height()))
        .containsExactly(source);
  }

  static Stream<SourceInBox> sourcesWithinTheirFixedQualitysBox() {
    return Stream.of(
        SourceInBox.builder()
            .source(new PictureSize(1080, 1920))
            .quality(VideoQuality.FULL_HD_1080P)
            .build(),
        SourceInBox.builder()
            .source(new PictureSize(1920, 1080))
            .quality(VideoQuality.UHD_4K)
            .build());
  }

  @Builder
  record SourceInBox(PictureSize source, VideoQuality quality) {}

  @Test
  @DisplayName(
      "Should encode a fixed quality within the viewer's maximum bitrate when the source's bitrate"
          + " is higher")
  void shouldEncodeAFixedQualityWithinTheViewersMaximumBitrateWhenTheSourcesBitrateIsHigher() {
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
    var options =
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).maxBitrate(1_000_000).build();

    var variants = generateVariants(probe, options);

    assertThat(variants).extracting(QualityVariant::videoBitrate).containsExactly(1_000_000L);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("picturesAndTheirSmallestTiersBitrate")
  @DisplayName(
      "Should encode at the nominal bitrate of the smallest tier whose box holds the picture when"
          + " the probe names no source bitrate")
  void
      shouldEncodeAtTheNominalBitrateOfTheSmallestTierWhoseBoxHoldsThePictureWhenTheProbeNamesNoSourceBitrate(
          PictureBitrate expected) {
    var picture = expected.picture();
    var probe = buildProbeBuilder(picture.width(), picture.height()).bitrate(0L).build();
    var options = StreamingOptions.builder().quality(VideoQuality.ORIGINAL).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::videoBitrate)
        .containsExactly(expected.bitrate());
  }

  // ADR 0050's H.264 tier bitrates. A 1000×562 picture fits the 720p box, not the 480p box.
  static Stream<PictureBitrate> picturesAndTheirSmallestTiersBitrate() {
    return Stream.of(
        pictureBitrate(new PictureSize(3840, 2160), 20_000_000L),
        pictureBitrate(new PictureSize(2560, 1440), 12_000_000L),
        pictureBitrate(new PictureSize(1920, 1080), 5_000_000L),
        pictureBitrate(new PictureSize(1280, 720), 3_000_000L),
        pictureBitrate(new PictureSize(1000, 562), 3_000_000L),
        pictureBitrate(new PictureSize(854, 480), 1_500_000L),
        pictureBitrate(new PictureSize(320, 180), 800_000L));
  }

  private static PictureBitrate pictureBitrate(PictureSize picture, long bitrate) {
    return PictureBitrate.builder().picture(picture).bitrate(bitrate).build();
  }

  @Builder
  record PictureBitrate(PictureSize picture, long bitrate) {}

  @ParameterizedTest
  @CsvSource({
    "LOW_360P, 800000",
    "HIGH_720P, 3000000",
    "FULL_HD_1080P, 5000000",
    "UHD_4K, 5000000"
  })
  @DisplayName(
      "Should encode a fixed quality at the nominal bitrate of the tier that holds the encoded"
          + " picture when the probe names no source bitrate")
  void
      shouldEncodeAFixedQualityAtTheNominalBitrateOfTheTierThatHoldsTheEncodedPictureWhenTheProbeNamesNoSourceBitrate(
          VideoQuality quality, long expectedBitrate) {
    var probe = buildProbeBuilder(1920, 1080).bitrate(0L).build();
    var options = StreamingOptions.builder().quality(quality).build();

    var variants = generateVariants(probe, options);

    assertThat(variants).extracting(QualityVariant::videoBitrate).containsExactly(expectedBitrate);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fixedQualitiesOfSourcesAboveTheirTiersBitrate")
  @DisplayName(
      "Should encode a fixed quality at the nominal bitrate of the smallest tier whose box holds"
          + " the encoded picture in either orientation when the source names a higher bitrate")
  void
      shouldEncodeAFixedQualityAtTheNominalBitrateOfTheSmallestTierWhoseBoxHoldsTheEncodedPictureInEitherOrientationWhenTheSourceNamesAHigherBitrate(
          FixedQualityBitrate fixedQuality) {
    var source = fixedQuality.source();
    var probe = buildProbeBuilder(source.width(), source.height()).bitrate(40_000_000L).build();
    var options = StreamingOptions.builder().quality(fixedQuality.quality()).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::videoBitrate)
        .containsExactly(fixedQuality.videoBitrate());
  }

  // A fixed 4K of a 1080p source encodes a picture that the 1080p box holds, upright or not, and a
  // fixed 1080p of a 1280×536 source one that the 720p box holds.
  static Stream<FixedQualityBitrate> fixedQualitiesOfSourcesAboveTheirTiersBitrate() {
    return Stream.of(
        FixedQualityBitrate.builder()
            .source(new PictureSize(1920, 1080))
            .quality(VideoQuality.UHD_4K)
            .videoBitrate(5_000_000L)
            .build(),
        FixedQualityBitrate.builder()
            .source(new PictureSize(1080, 1920))
            .quality(VideoQuality.UHD_4K)
            .videoBitrate(5_000_000L)
            .build(),
        FixedQualityBitrate.builder()
            .source(new PictureSize(1280, 536))
            .quality(VideoQuality.FULL_HD_1080P)
            .videoBitrate(3_000_000L)
            .build(),
        FixedQualityBitrate.builder()
            .source(new PictureSize(3840, 2160))
            .quality(VideoQuality.UHD_4K)
            .videoBitrate(20_000_000L)
            .build());
  }

  @Builder
  record FixedQualityBitrate(PictureSize source, VideoQuality quality, long videoBitrate) {}

  @ParameterizedTest
  @CsvSource({"20000000, 5000000", "2000000, 2000000"})
  @DisplayName(
      "Should encode at the lower of the nominal bitrate and the viewer's maximum when the probe"
          + " names no source bitrate")
  void
      shouldEncodeAtTheLowerOfTheNominalBitrateAndTheViewersMaximumWhenTheProbeNamesNoSourceBitrate(
          int maxBitrate, long expectedBitrate) {
    var probe = buildProbeBuilder(1920, 1080).bitrate(0L).build();
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
    var probe = buildProbeBuilder(1920, 1080).bitrate(0L).build();
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
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
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
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
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
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
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
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
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
  @DisplayName("Should return single variant when source resolution is low")
  void shouldReturnSingleVariantWhenSourceResolutionIsLow() {
    var probe = buildProbeBuilder(320, 180).bitrate(500_000L).build();
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
  @ParameterizedTest(name = "{0}")
  @MethodSource("audioBitratesAndTheTiersTheyLeave")
  @DisplayName(
      "Should offer a tier only when its video bitrate plus the session's audio bitrate is within"
          + " the viewer's maximum bitrate")
  void shouldOfferATierOnlyWhenItsVideoPlusAudioBitrateIsWithinTheViewersMaximumBitrate(
      TiersWithinBudget budget) {
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
    var options = StreamingOptions.builder().maxBitrate(budget.maxBitrate()).build();

    var variants =
        service.generateVariants(
            ladderRequest(probe, options).audioBitrate(budget.audioBitrate()).build());

    assertThat(variants)
        .extracting(QualityVariant::label)
        .containsExactlyElementsOf(budget.labels());
  }

  // 720p's 3 Mb/s fits a 3.1 Mb/s maximum only without audio, and 480p's 1.5 Mb/s fits 1.8 Mb/s
  // only with less than 300 kb/s of audio.
  static Stream<TiersWithinBudget> audioBitratesAndTheTiersTheyLeave() {
    return Stream.of(
        TiersWithinBudget.builder()
            .audioBitrate(0L)
            .maxBitrate(3_100_000)
            .labels(List.of("720p", "480p", "360p"))
            .build(),
        TiersWithinBudget.builder()
            .audioBitrate(128_000L)
            .maxBitrate(3_100_000)
            .labels(List.of("480p", "360p"))
            .build(),
        TiersWithinBudget.builder()
            .audioBitrate(384_000L)
            .maxBitrate(1_800_000)
            .labels(List.of("360p"))
            .build());
  }

  @Builder
  record TiersWithinBudget(long audioBitrate, int maxBitrate, List<String> labels) {}

  // ADR 0050: a single variant's video gets at most the maximum bitrate less the audio bitrate.
  @ParameterizedTest(name = "{0}")
  @MethodSource("budgetsThatHoldThe360pTiersBitrate")
  @DisplayName(
      "Should encode the single variant at the maximum bitrate less the audio when that leaves at"
          + " least the 360p tier's bitrate")
  void
      shouldEncodeTheSingleVariantAtTheMaximumBitrateLessTheAudioWhenThatLeavesAtLeastThe360pTiersBitrate(
          SingleVariantBudget budget) {
    assertThat(singleVariantWithin(budget))
        .containsExactly(
            tuple(budget.picture().width(), budget.picture().height(), budget.videoBitrate()));
  }

  static Stream<SingleVariantBudget> budgetsThatHoldThe360pTiersBitrate() {
    return Stream.of(
        fixed1080pBudget().maxBitrate(2_000_000).videoBitrate(1_872_000L).build(),
        fixed1080pBudget().maxBitrate(928_000).videoBitrate(800_000L).build());
  }

  // An encoder reads a zero bitrate as no limit, so the video never gets the remainder when the
  // audio leaves less than the 360p tier's bitrate, or nothing at all.
  @ParameterizedTest(name = "{0}")
  @MethodSource("budgetsBelowThe360pTiersBitrate")
  @DisplayName(
      "Should encode the single variant in the 360p box at the 360p tier's bitrate within the"
          + " maximum when the audio leaves less than that bitrate")
  void
      shouldEncodeTheSingleVariantInThe360pBoxAtThe360pTiersBitrateWithinTheMaximumWhenTheAudioLeavesLessThanThatBitrate(
          SingleVariantBudget budget) {
    assertThat(singleVariantWithin(budget)).containsExactly(tuple(640, 360, budget.videoBitrate()));
  }

  static Stream<SingleVariantBudget> budgetsBelowThe360pTiersBitrate() {
    return Stream.of(
        fixed1080pBudget().maxBitrate(927_999).videoBitrate(800_000L).build(),
        fixed1080pBudget().maxBitrate(700_000).videoBitrate(700_000L).build(),
        fixed1080pBudget().maxBitrate(128_000).videoBitrate(128_000L).build(),
        fixed1080pBudget()
            .audioBitrate(384_000L)
            .maxBitrate(100_000)
            .videoBitrate(100_000L)
            .build(),
        fixed1080pBudget().sourceBitrate(0L).maxBitrate(500_000).videoBitrate(500_000L).build(),
        fixed1080pBudget()
            .quality(VideoQuality.AUTO)
            .maxBitrate(500_000)
            .videoBitrate(500_000L)
            .build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("budgetsAboveTheSourcesBitrate")
  @DisplayName(
      "Should cap the single variant at the source's known bitrate when the budget leaves more")
  void shouldCapTheSingleVariantAtTheSourcesKnownBitrateWhenTheBudgetLeavesMore(
      SingleVariantBudget budget) {
    assertThat(singleVariantWithin(budget))
        .containsExactly(tuple(640, 360, budget.sourceBitrate()));
  }

  static Stream<SingleVariantBudget> budgetsAboveTheSourcesBitrate() {
    return Stream.of(
        fixed1080pBudget().sourceBitrate(300_000L).maxBitrate(500_000).build(),
        fixed1080pBudget()
            .quality(VideoQuality.AUTO)
            .sourceBitrate(300_000L)
            .maxBitrate(500_000)
            .build());
  }

  private List<Tuple> singleVariantWithin(SingleVariantBudget budget) {
    var probe = buildProbeBuilder(1920, 1080).bitrate(budget.sourceBitrate()).build();
    var options =
        StreamingOptions.builder()
            .quality(budget.quality())
            .maxBitrate(budget.maxBitrate())
            .build();

    var variants =
        service.generateVariants(
            ladderRequest(probe, options).audioBitrate(budget.audioBitrate()).build());

    return variants.stream()
        .map(variant -> tuple(variant.width(), variant.height(), variant.videoBitrate()))
        .toList();
  }

  // A fixed 1080p of an 8 Mb/s 1920×1080 source beside 128 kb/s of audio, at the full picture.
  private static SingleVariantBudget.SingleVariantBudgetBuilder fixed1080pBudget() {
    return SingleVariantBudget.builder()
        .quality(VideoQuality.FULL_HD_1080P)
        .sourceBitrate(8_000_000L)
        .audioBitrate(128_000L)
        .picture(new PictureSize(1920, 1080));
  }

  @Builder
  record SingleVariantBudget(
      VideoQuality quality,
      long sourceBitrate,
      long audioBitrate,
      int maxBitrate,
      PictureSize picture,
      long videoBitrate) {}

  @Test
  @DisplayName("Should filter tiers when client specifies max bitrate")
  void shouldFilterTiersWhenClientSpecifiesMaxBitrate() {
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
    var options = StreamingOptions.builder().maxBitrate(2_000_000).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.videoBitrate()).isLessThanOrEqualTo(2_000_000));
  }

  @Test
  @DisplayName("Should generate correct bitrates when source is 1080p")
  void shouldGenerateCorrectBitratesWhenSourceIs1080p() {
    var probe = buildProbeBuilder(1920, 1080).bitrate(8_000_000L).build();
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
    var probe = buildProbeBuilder(source.width(), source.height()).bitrate(8_000_000L).build();
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
    var probe = buildProbeBuilder(7680, 4320).bitrate(80_000_000L).build();

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
    var probe = buildProbeBuilder(319, 179).bitrate(500_000L).build();
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(318);
    assertThat(variants.get(0).height()).isEqualTo(178);
    assertThat(variants.get(0).label()).isEqualTo("178p");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("variantSizesAndTheirLabels")
  @DisplayName(
      "Should label the variant by the tier whose box it reaches, or else by its shorter side, when"
          + " the source is the variant's size")
  void
      shouldLabelTheVariantByTheTierWhoseBoxItReachesOrElseByItsShorterSideWhenTheSourceIsTheVariantsSize(
          LabelledPicture expected) {
    var picture = expected.picture();
    var probe = buildProbeBuilder(picture.width(), picture.height()).bitrate(8_000_000L).build();

    var variants = generateVariants(probe, StreamingOptions.builder().build());

    assertThat(variants.getFirst())
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(picture.width(), picture.height(), expected.label());
  }

  // ADR 0050's labelling examples. A 638×360 picture reaches the 360p box because the worker's
  // next larger picture of it is too tall.
  static Stream<LabelledPicture> variantSizesAndTheirLabels() {
    return Stream.of(
        labelledPicture(new PictureSize(640, 360), "360p"),
        labelledPicture(new PictureSize(360, 640), "360p"),
        labelledPicture(new PictureSize(638, 360), "360p"),
        labelledPicture(new PictureSize(320, 180), "180p"),
        labelledPicture(new PictureSize(180, 320), "180p"),
        labelledPicture(new PictureSize(1920, 800), "1080p"),
        labelledPicture(new PictureSize(2560, 1072), "1440p"));
  }

  private static LabelledPicture labelledPicture(PictureSize picture, String label) {
    return LabelledPicture.builder().picture(picture).label(label).build();
  }

  @Builder
  record LabelledPicture(PictureSize picture, String label) {}

  @Test
  @DisplayName(
      "Should label the only variant by its shorter side when the viewer's maximum width bounds a"
          + " portrait picture")
  void shouldLabelTheOnlyVariantByItsShorterSideWhenTheViewersMaximumWidthBoundsAPortraitPicture() {
    var probe = buildProbeBuilder(1080, 1920).bitrate(8_000_000L).build();
    var options = StreamingOptions.builder().maxWidth(300).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(300, 534, "300p"));
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
