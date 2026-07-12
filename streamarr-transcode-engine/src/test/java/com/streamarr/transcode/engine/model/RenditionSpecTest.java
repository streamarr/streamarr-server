package com.streamarr.transcode.engine.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Rendition Specification Tests")
class RenditionSpecTest {

  @Test
  @DisplayName("Should preserve rendition intent when values are valid")
  void shouldPreserveRenditionIntentWhenValuesAreValid() {
    var rendition = new RenditionSpec("720p", 1280, 720, 3_000_000L);

    assertThat(rendition.label()).isEqualTo("720p");
    assertThat(rendition.width()).isEqualTo(1280);
    assertThat(rendition.height()).isEqualTo(720);
    assertThat(rendition.videoBitrate()).isEqualTo(3_000_000L);
  }

  @ParameterizedTest
  @MethodSource("invalidRenditions")
  @DisplayName("Should reject rendition specification when values are invalid")
  void shouldRejectRenditionSpecificationWhenValuesAreInvalid(
      String label, int width, int height, long videoBitrate) {
    assertThatThrownBy(() -> new RenditionSpec(label, width, height, videoBitrate))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("unsafePortableLabels")
  @DisplayName("Should reject rendition specification when label is not one portable segment")
  void shouldRejectRenditionSpecificationWhenLabelIsNotOnePortableSegment(String label) {
    assertThatThrownBy(() -> new RenditionSpec(label, 1280, 720, 3_000_000L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static Stream<Arguments> invalidRenditions() {
    return Stream.of(
        Arguments.of(null, 1280, 720, 3_000_000L),
        Arguments.of(" ", 1280, 720, 3_000_000L),
        Arguments.of("720p", 0, 720, 3_000_000L),
        Arguments.of("720p", 1280, 0, 3_000_000L),
        Arguments.of("720p", 1280, 720, 0L),
        Arguments.of("720p", 1280, 720, Long.MAX_VALUE / 2 + 1));
  }

  static Stream<String> unsafePortableLabels() {
    return Stream.of("720/p", "720\\p", ".", "..", "720\0p");
  }
}
