package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.domain.streaming.ChromaSubsampling;
import com.streamarr.server.domain.streaming.ContainerCropping;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamingOptions;
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

    var variants = service.generateVariants(probe, options);

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

    var variants = service.generateVariants(probe, options);

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

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(2);
    assertThat(variants.get(0).label()).isEqualTo("480p");
    assertThat(variants.get(1).label()).isEqualTo("360p");
  }

  @Test
  @DisplayName("Should size the only variant at the maximum height when it is below every tier")
  void shouldSizeTheOnlyVariantAtTheMaximumHeightWhenItIsBelowEveryTier() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxHeight(300).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(534, 300, "300p"));
  }

  @Test
  @DisplayName("Should return single variant when source resolution is low")
  void shouldReturnSingleVariantWhenSourceResolutionIsLow() {
    var probe = buildProbe(320, 180, 500_000L);
    var options = StreamingOptions.builder().build();

    var variants = service.generateVariants(probe, options);

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

    assertThat(service.generateVariants(probe, options))
        .extracting(QualityVariant::width, QualityVariant::height, QualityVariant::label)
        .containsExactly(tuple(expectedWidth, 236, "236p"));
  }

  @Test
  @DisplayName("Should filter tiers when client specifies max bitrate")
  void shouldFilterTiersWhenClientSpecifiesMaxBitrate() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxBitrate(2_000_000).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.videoBitrate()).isLessThanOrEqualTo(2_000_000));
  }

  @Test
  @DisplayName("Should generate correct bitrates when source is 1080p")
  void shouldGenerateCorrectBitratesWhenSourceIs1080p() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().build();

    var variants = service.generateVariants(probe, options);

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

    var variants = service.generateVariants(probe, options);

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

    var variants = service.generateVariants(probe, options);

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

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .extracting(QualityVariant::width, QualityVariant::height)
        .containsExactly(tuple(1728, 720), tuple(1152, 480), tuple(864, 360));
  }

  @Test
  @DisplayName("Should align width to even number when source resolution is below all tiers")
  void shouldAlignWidthToEvenNumberWhenSourceResolutionIsBelowAllTiers() {
    var probe = buildProbe(319, 179, 500_000L);
    var options = StreamingOptions.builder().build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(318);
    assertThat(variants.get(0).height()).isEqualTo(178);
    assertThat(variants.get(0).label()).isEqualTo("178p");
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
