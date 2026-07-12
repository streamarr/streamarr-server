package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transcode Request Tests")
class TranscodeRequestTest {

  @Test
  @DisplayName("Should use default variant when label is absent")
  void shouldUseDefaultVariantWhenLabelIsAbsent() {
    var request = TranscodeRequest.builder().build();

    assertThat(request.variantLabel()).isEqualTo(TranscodeRequest.DEFAULT_VARIANT);
  }

  @Test
  @DisplayName("Should preserve explicit variant label")
  void shouldPreserveExplicitVariantLabel() {
    var request = TranscodeRequest.builder().variantLabel("720p").build();

    assertThat(request.variantLabel()).isEqualTo("720p");
  }
}
