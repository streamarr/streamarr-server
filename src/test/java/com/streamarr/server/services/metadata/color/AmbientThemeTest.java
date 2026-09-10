package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.AmbientTheme;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ambient Theme Tests")
class AmbientThemeTest {

  @ParameterizedTest
  @ValueSource(
      strings = {"base", "panel", "selected", "accent", "onAccent", "textPrimary", "textSecondary"})
  @DisplayName("Should reject an incomplete theme when a required slot is omitted")
  void shouldRejectIncompleteThemeWhenRequiredSlotIsOmitted(String slot) {
    var builder = AmbientTheme.builder();
    Map<String, Consumer<AmbientTheme.AmbientThemeBuilder>> components =
        Map.of(
            "base", target -> target.base("#010101"),
            "panel", target -> target.panel("#020202"),
            "selected", target -> target.selected("#030303"),
            "accent", target -> target.accent("#040404"),
            "onAccent", target -> target.onAccent("#050505"),
            "textPrimary", target -> target.textPrimary("#060606"),
            "textSecondary", target -> target.textSecondary("#070707"));
    components.entrySet().stream()
        .filter(entry -> !entry.getKey().equals(slot))
        .forEach(entry -> entry.getValue().accept(builder));

    assertThatNullPointerException().isThrownBy(builder::build).withMessageContaining(slot);
  }
}
