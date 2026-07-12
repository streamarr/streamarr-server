package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.transcode.engine.model.ContainerFormat;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
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
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

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
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

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
    var options =
        StreamingOptions.builder().maxHeight(480).supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(2);
    assertThat(variants.get(0).label()).isEqualTo("480p");
    assertThat(variants.get(1).label()).isEqualTo("360p");
  }

  @Test
  @DisplayName("Should filter tiers when client specifies max width")
  void shouldFilterTiersWhenClientSpecifiesMaxWidth() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxWidth(1_000).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .isNotEmpty()
        .allSatisfy(variant -> assertThat(variant.width()).isLessThanOrEqualTo(1_000));
    assertThat(variants.getFirst().height()).isEqualTo(480);
  }

  @Test
  @DisplayName("Should honor max width when it filters every standard tier")
  void shouldHonorMaxWidthWhenItFiltersEveryStandardTier() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxWidth(400).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .singleElement()
        .satisfies(
            variant -> {
              assertThat(variant.width()).isEqualTo(400);
              assertThat(variant.height()).isEqualTo(225);
            });
  }

  @Test
  @DisplayName("Should return single variant when source resolution is low")
  void shouldReturnSingleVariantWhenSourceResolutionIsLow() {
    var probe = buildProbe(320, 180, 500_000L);
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(320);
    assertThat(variants.get(0).height()).isEqualTo(180);
  }

  @Test
  @DisplayName("Should filter tiers when client specifies max bitrate")
  void shouldFilterTiersWhenClientSpecifiesMaxBitrate() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options =
        StreamingOptions.builder().maxBitrate(2_000_000).supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .isNotEmpty()
        .allSatisfy(v -> assertThat(v.videoBitrate()).isLessThanOrEqualTo(2_000_000));
  }

  @Test
  @DisplayName("Should honor max bitrate when it filters every standard tier")
  void shouldHonorMaxBitrateWhenItFiltersEveryStandardTier() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxBitrate(500_000).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .singleElement()
        .extracting(com.streamarr.transcode.engine.model.QualityVariant::videoBitrate)
        .isEqualTo(500_000L);
  }

  @Test
  @DisplayName("Should honor max height when it filters every standard tier")
  void shouldHonorMaxHeightWhenItFiltersEveryStandardTier() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().maxHeight(240).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants)
        .singleElement()
        .satisfies(
            variant -> {
              assertThat(variant.width()).isEqualTo(428);
              assertThat(variant.height()).isEqualTo(240);
            });
  }

  @Test
  @DisplayName("Should generate correct bitrates when source is 1080p")
  void shouldGenerateCorrectBitratesWhenSourceIs1080p() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants.get(0).videoBitrate()).isEqualTo(5_000_000L);
    assertThat(variants.get(1).videoBitrate()).isEqualTo(3_000_000L);
    assertThat(variants.get(2).videoBitrate()).isEqualTo(1_500_000L);
    assertThat(variants.get(3).videoBitrate()).isEqualTo(800_000L);
  }

  @ParameterizedTest
  @EnumSource(VideoQuality.class)
  @DisplayName("Should derive unavailable source bitrate from requested quality")
  void shouldDeriveUnavailableSourceBitrateFromRequestedQuality(VideoQuality quality) {
    var session =
        defaultSessionBuilder()
            .mediaProbe(defaultProbeBuilder().bitrate(0).build())
            .transcodeDecision(fullTranscodeDecision("h264", ContainerFormat.MPEGTS))
            .options(StreamingOptions.builder().quality(quality).build())
            .build();
    var expected =
        switch (quality) {
          case LOW_360P -> 800_000L;
          case MEDIUM_480P -> 1_500_000L;
          case HIGH_720P -> 3_000_000L;
          case AUTO, FULL_HD_1080P, UHD_4K -> 5_000_000L;
        };

    assertThat(service.resolveDefaultRendition(session).videoBitrate()).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should cap fallback bitrate by client constraints")
  void shouldCapFallbackBitrateByClientConstraints() {
    var session =
        defaultSessionBuilder()
            .mediaProbe(defaultProbeBuilder().bitrate(0).build())
            .transcodeDecision(fullTranscodeDecision("h264", ContainerFormat.MPEGTS))
            .options(
                StreamingOptions.builder()
                    .quality(VideoQuality.AUTO)
                    .maxHeight(720)
                    .maxBitrate(2_000_000)
                    .build())
            .build();

    assertThat(service.resolveDefaultRendition(session).videoBitrate()).isEqualTo(2_000_000L);
  }

  @Test
  @DisplayName("Should use lowest standard bitrate when source is below standard tiers")
  void shouldUseLowestStandardBitrateWhenSourceIsBelowStandardTiers() {
    var session =
        defaultSessionBuilder()
            .mediaProbe(defaultProbeBuilder().height(180).bitrate(0).build())
            .transcodeDecision(fullTranscodeDecision("h264", ContainerFormat.MPEGTS))
            .options(StreamingOptions.builder().quality(VideoQuality.AUTO).build())
            .build();

    assertThat(service.resolveDefaultRendition(session).videoBitrate()).isEqualTo(800_000L);
  }

  @Test
  @DisplayName("Should resolve an explicit UHD rendition when source supports it")
  void shouldResolveExplicitUhdRenditionWhenSourceSupportsIt() {
    var session =
        defaultSessionBuilder()
            .mediaProbe(defaultProbeBuilder().width(3840).height(2160).bitrate(20_000_000).build())
            .transcodeDecision(fullTranscodeDecision("h264", ContainerFormat.MPEGTS))
            .options(StreamingOptions.builder().quality(VideoQuality.UHD_4K).build())
            .build();

    var rendition = service.resolveDefaultRendition(session);

    assertThat(rendition.width()).isEqualTo(3840);
    assertThat(rendition.height()).isEqualTo(2160);
    assertThat(rendition.videoBitrate()).isEqualTo(15_000_000L);
  }

  @Test
  @DisplayName("Should preserve known aggregate bandwidth")
  void shouldPreserveKnownAggregateBandwidth() {
    var session = defaultSessionBuilder().build();

    assertThat(service.resolveDefaultRendition(session).videoBitrate()).isEqualTo(5_000_000L);
    assertThat(service.resolveDefaultRenditionBandwidth(session)).isEqualTo(5_000_000L);
  }

  @Test
  @DisplayName("Should use a positive sentinel for copied video with unavailable bitrate")
  void shouldUsePositiveSentinelForCopiedVideoWithUnavailableBitrate() {
    var session =
        defaultSessionBuilder().mediaProbe(defaultProbeBuilder().bitrate(0).build()).build();

    assertThat(service.resolveDefaultRendition(session).videoBitrate()).isOne();
    assertThat(service.resolveDefaultRenditionBandwidth(session)).isOne();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("aspectRatioVariants")
  @DisplayName(
      "Should compute aspect-ratio-correct dimensions when generating variants for each quality tier")
  void shouldComputeAspectRatioCorrectDimensionsWhenGeneratingVariantsForEachQualityTier(
      String scenario, int sourceWidth, int sourceHeight, List<Integer> expectedWidths) {
    var probe = buildProbe(sourceWidth, sourceHeight, 8_000_000L);
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    var expectedHeights = STANDARD_TIER_HEIGHTS.stream().filter(h -> h <= sourceHeight).toList();
    assertThat(variants).hasSize(expectedWidths.size());
    for (int i = 0; i < variants.size(); i++) {
      assertThat(variants.get(i).width()).isEqualTo(expectedWidths.get(i));
      assertThat(variants.get(i).height()).isEqualTo(expectedHeights.get(i));
    }
  }

  static Stream<Arguments> aspectRatioVariants() {
    return Stream.of(
        Arguments.of("16:9 source", 1920, 1080, List.of(1920, 1280, 854, 640)),
        Arguments.of("4:3 source", 1440, 1080, List.of(1440, 960, 640, 480)),
        Arguments.of("21:9 ultrawide source", 2560, 1080, List.of(2560, 1708, 1138, 854)),
        Arguments.of("1:1 square source", 1080, 1080, List.of(1080, 720, 480, 360)),
        Arguments.of("9:16 portrait source", 1080, 1920, List.of(608, 406, 270, 204)),
        Arguments.of("4:3 at 720p", 960, 720, List.of(960, 640, 480)));
  }

  @Test
  @DisplayName("Should align width to even number when source resolution is below all tiers")
  void shouldAlignWidthToEvenNumberWhenSourceResolutionIsBelowAllTiers() {
    var probe = buildProbe(319, 179, 500_000L);
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(320);
    assertThat(variants.get(0).height()).isEqualTo(179);
    assertThat(variants.get(0).label()).isEqualTo("179p");
  }

  private MediaProbe buildProbe(int width, int height, long bitrate) {
    return MediaProbe.builder()
        .duration(Duration.ofMinutes(120))
        .framerate(24.0)
        .width(width)
        .height(height)
        .videoCodec("h264")
        .audioCodec("aac")
        .bitrate(bitrate)
        .build();
  }
}
