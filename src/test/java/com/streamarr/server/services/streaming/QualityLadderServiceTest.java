package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
class QualityLadderServiceTest {

  private final QualityLadderService service = new QualityLadderService();

  @Test
  @DisplayName("shouldGenerateAllTiersFor1080pSource")
  void shouldGenerateAllTiersFor1080pSource() {
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
  @DisplayName("shouldCapAt720pForSourceBelow1080p")
  void shouldCapAt720pForSourceBelow1080p() {
    var probe = buildProbe(1280, 720, 4_000_000L);
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(3);
    assertThat(variants.get(0).label()).isEqualTo("720p");
    assertThat(variants.get(1).label()).isEqualTo("480p");
    assertThat(variants.get(2).label()).isEqualTo("360p");
  }

  @Test
  @DisplayName("shouldFilterByClientMaxHeight")
  void shouldFilterByClientMaxHeight() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options =
        StreamingOptions.builder().maxHeight(480).supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(2);
    assertThat(variants.get(0).label()).isEqualTo("480p");
    assertThat(variants.get(1).label()).isEqualTo("360p");
  }

  @Test
  @DisplayName("shouldReturnSingleVariantForLowResSource")
  void shouldReturnSingleVariantForLowResSource() {
    var probe = buildProbe(320, 180, 500_000L);
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).hasSize(1);
    assertThat(variants.get(0).width()).isEqualTo(320);
    assertThat(variants.get(0).height()).isEqualTo(180);
  }

  @Test
  @DisplayName("shouldFilterByClientMaxBitrate")
  void shouldFilterByClientMaxBitrate() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options =
        StreamingOptions.builder().maxBitrate(2_000_000).supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants).allSatisfy(v -> assertThat(v.videoBitrate()).isLessThanOrEqualTo(2_000_000));
    assertThat(variants).isNotEmpty();
  }

  @Test
  @DisplayName("shouldGenerateCorrectBitratesPerTier")
  void shouldGenerateCorrectBitratesPerTier() {
    var probe = buildProbe(1920, 1080, 8_000_000L);
    var options = StreamingOptions.builder().supportedCodecs(List.of("h264")).build();

    var variants = service.generateVariants(probe, options);

    assertThat(variants.get(0).videoBitrate()).isEqualTo(5_000_000L);
    assertThat(variants.get(1).videoBitrate()).isEqualTo(3_000_000L);
    assertThat(variants.get(2).videoBitrate()).isEqualTo(1_500_000L);
    assertThat(variants.get(3).videoBitrate()).isEqualTo(800_000L);
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
