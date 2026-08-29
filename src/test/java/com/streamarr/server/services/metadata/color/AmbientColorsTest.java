package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.AmbientColors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ambient Colors Tests")
class AmbientColorsTest {

  @Test
  @DisplayName("Should leave target swatches absent when built without them")
  void shouldLeaveTargetSwatchesAbsentWhenBuiltWithoutThem() {
    var colors = baseColors().build();

    assertThat(colors.darkVibrant()).isNull();
    assertThat(colors.darkMuted()).isNull();
    assertThat(colors.lightVibrant()).isNull();
    assertThat(colors.lightMuted()).isNull();
  }

  @Test
  @DisplayName("Should carry target swatches when built with them")
  void shouldCarryTargetSwatchesWhenBuiltWithThem() {
    var colors =
        baseColors()
            .darkVibrant("#103070")
            .darkMuted("#283830")
            .lightVibrant("#68f8f8")
            .lightMuted("#c8d0c8")
            .build();

    assertThat(colors.darkVibrant()).isEqualTo("#103070");
    assertThat(colors.darkMuted()).isEqualTo("#283830");
    assertThat(colors.lightVibrant()).isEqualTo("#68f8f8");
    assertThat(colors.lightMuted()).isEqualTo("#c8d0c8");
  }

  @ParameterizedTest
  @ValueSource(strings = {"topLeft", "topRight", "bottomRight", "bottomLeft", "primary"})
  @DisplayName("Should reject a missing component when assigned")
  void shouldRejectMissingComponentWhenAssigned(String component) {
    var builder = baseColors();

    assertThatNullPointerException()
        .isThrownBy(
            () -> {
              switch (component) {
                case "topLeft" -> builder.topLeft(null);
                case "topRight" -> builder.topRight(null);
                case "bottomRight" -> builder.bottomRight(null);
                case "bottomLeft" -> builder.bottomLeft(null);
                case "primary" -> builder.primary(null);
                default -> throw new IllegalArgumentException(component);
              }
            })
        .withMessageContaining(component);
  }

  private static AmbientColors.AmbientColorsBuilder baseColors() {
    return AmbientColors.builder()
        .topLeft("#010101")
        .topRight("#020202")
        .bottomRight("#030303")
        .bottomLeft("#040404")
        .primary("#050505");
  }
}
