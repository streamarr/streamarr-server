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

  private static final List<Integer> STANDARD_TIER_HEIGHTS = List.of(1080, 720, 480, 360);

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

  @Test
  @DisplayName("Should offer only the source-sized default variant when the quality is fixed")
  void shouldOfferOnlyTheSourceSizedDefaultVariantWhenTheQualityIsFixed() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();

    var variants = generateVariants(probe, options);

    assertThat(variants)
        .extracting(
            QualityVariant::width,
            QualityVariant::height,
            QualityVariant::videoBitrate,
            QualityVariant::label)
        .containsExactly(tuple(1920, 1080, 8_000_000L, StreamSession.defaultVariant()));
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
    "3840, 2160, 5000000",
    "1920, 1080, 5000000",
    "1280, 720, 3000000",
    "1000, 562, 3000000",
    "854, 480, 1500000",
    "320, 180, 800000"
  })
  @DisplayName(
      "Should encode at the nominal bitrate of the tier at or above the fitted height when the"
          + " probe names no source bitrate")
  void
      shouldEncodeAtTheNominalBitrateOfTheTierAtOrAboveTheFittedHeightWhenTheProbeNamesNoSourceBitrate(
          int width, int height, long expectedBitrate) {
    var probe = buildProbe(width, height, 0L);
    var options = StreamingOptions.builder().quality(VideoQuality.HIGH_720P).build();

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
        StreamingOptions.builder().quality(VideoQuality.HIGH_720P).maxBitrate(maxBitrate).build();

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
  @DisplayName("Should label a capped variant by its height when no smaller tier has that size")
  void shouldLabelACappedVariantByItsHeightWhenNoSmallerTierHasThatSize() {
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
  @DisplayName("Should round the output height down when a cropped source is below every tier")
  void shouldRoundTheOutputHeightDownWhenACroppedSourceIsBelowEveryTier(
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
      String scenario, int sourceWidth, int sourceHeight, List<Integer> expectedWidths) {
    var probe = buildProbe(sourceWidth, sourceHeight, 8_000_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    var expectedHeights = STANDARD_TIER_HEIGHTS.stream().filter(h -> h <= sourceHeight).toList();
    assertThat(variants).hasSize(expectedWidths.size());
    for (int i = 0; i < variants.size(); i++) {
      assertThat(variants.get(i).width()).isEqualTo(expectedWidths.get(i));
      assertThat(variants.get(i).height()).isEqualTo(expectedHeights.get(i));
    }
  }

  // FFmpeg's scale=-2:<height> divides the height times the aspect ratio by 2, rounds half away
  // from zero, and doubles the result. FFmpeg 8.0.1 wrote these widths from synthetic frames.
  static Stream<Arguments> aspectRatioVariants() {
    return Stream.of(
        Arguments.of("16:9 source", 1920, 1080, List.of(1920, 1280, 854, 640)),
        Arguments.of("4:3 source", 1440, 1080, List.of(1440, 960, 640, 480)),
        Arguments.of("21:9 ultrawide source", 2560, 1080, List.of(2560, 1706, 1138, 854)),
        Arguments.of("1:1 square source", 1080, 1080, List.of(1080, 720, 480, 360)),
        Arguments.of("9:16 portrait source", 1080, 1920, List.of(608, 406, 270, 202)),
        Arguments.of("4:3 at 720p", 960, 720, List.of(960, 640, 480)));
  }

  @Test
  @DisplayName(
      "Should size the variants from the rotated picture when a phone recording is upright")
  void shouldSizeTheVariantsFromTheRotatedPictureWhenAPhoneRecordingIsUpright() {
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
        .extracting(QualityVariant::width, QualityVariant::height)
        .containsExactly(tuple(608, 1080), tuple(406, 720), tuple(270, 480), tuple(202, 360));
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
        .containsExactly(tuple(1728, 720), tuple(1152, 480), tuple(864, 360));
  }

  @Test
  @DisplayName("Should align width to even number when source resolution is below all tiers")
  void shouldAlignWidthToEvenNumberWhenSourceResolutionIsBelowAllTiers() {
    var probe = buildProbe(319, 179, 500_000L);
    var options = StreamingOptions.builder().build();

    var variants = generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(318);
    assertThat(variants.get(0).height()).isEqualTo(178);
    assertThat(variants.get(0).label()).isEqualTo("178p");
  }

  private List<QualityVariant> generateVariants(MediaProbe probe, StreamingOptions options) {
    return service.generateVariants(ladderRequest(probe, options).build());
  }

  // An H.264 ladder at 24 fps for a player whose only entry plays up to 3840×2160 at 60 fps.
  private static QualityLadderRequest.QualityLadderRequestBuilder ladderRequest(
      MediaProbe probe, StreamingOptions options) {
    return QualityLadderRequest.builder()
        .probe(probe)
        .options(options)
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
