package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Quality Variant Tests")
class QualityVariantTest {

  @Test
  @DisplayName("Should retain complete rendition quality")
  void shouldRetainCompleteRenditionQuality() {
    var variant =
        QualityVariant.builder()
            .width(1920)
            .height(1080)
            .videoBitrate(8_000_000)
            .audioBitrate(192_000)
            .label("1080p")
            .build();

    assertThat(variant.width()).isEqualTo(1920);
    assertThat(variant.height()).isEqualTo(1080);
    assertThat(variant.videoBitrate()).isEqualTo(8_000_000);
    assertThat(variant.audioBitrate()).isEqualTo(192_000);
    assertThat(variant.label()).isEqualTo("1080p");
  }
}
