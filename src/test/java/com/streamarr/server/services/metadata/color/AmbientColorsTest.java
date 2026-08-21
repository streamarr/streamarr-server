package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.AmbientColors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ambient Colors Tests")
class AmbientColorsTest {

  @ParameterizedTest
  @ValueSource(strings = {"topLeft", "topRight", "bottomRight", "bottomLeft", "primary"})
  @DisplayName("Should reject missing component when built")
  void shouldRejectMissingComponentWhenBuilt(String component) {
    var builder =
        AmbientColors.builder()
            .topLeft("#010101")
            .topRight("#020202")
            .bottomRight("#030303")
            .bottomLeft("#040404")
            .primary("#050505");

    var incompleteBuilder =
        switch (component) {
          case "topLeft" -> builder.topLeft(null);
          case "topRight" -> builder.topRight(null);
          case "bottomRight" -> builder.bottomRight(null);
          case "bottomLeft" -> builder.bottomLeft(null);
          case "primary" -> builder.primary(null);
          default -> throw new IllegalArgumentException(component);
        };

    assertThatNullPointerException().isThrownBy(incompleteBuilder::build).withMessage(component);
  }
}
