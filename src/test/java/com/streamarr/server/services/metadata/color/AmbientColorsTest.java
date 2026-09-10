package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.AmbientColors;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ambient Colors Tests")
class AmbientColorsTest {

  @ParameterizedTest
  @ValueSource(strings = {"topLeft", "topRight", "bottomRight", "bottomLeft", "primary"})
  @DisplayName("Should reject incomplete ambient colors when a required component is omitted")
  void shouldRejectIncompleteAmbientColorsWhenRequiredComponentIsOmitted(String component) {
    var builder = AmbientColors.builder();
    Map<String, Consumer<AmbientColors.AmbientColorsBuilder>> components =
        Map.of(
            "topLeft", target -> target.topLeft("#010101"),
            "topRight", target -> target.topRight("#020202"),
            "bottomRight", target -> target.bottomRight("#030303"),
            "bottomLeft", target -> target.bottomLeft("#040404"),
            "primary", target -> target.primary("#050505"));
    components.entrySet().stream()
        .filter(entry -> !entry.getKey().equals(component))
        .forEach(entry -> entry.getValue().accept(builder));

    assertThatNullPointerException().isThrownBy(builder::build).withMessageContaining(component);
  }
}
