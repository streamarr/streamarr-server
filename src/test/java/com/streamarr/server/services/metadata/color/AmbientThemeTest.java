package com.streamarr.server.services.metadata.color;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.server.domain.media.AmbientTheme;
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
  @DisplayName("Should reject a missing slot when assigned")
  void shouldRejectMissingSlotWhenAssigned(String slot) {
    var builder =
        AmbientTheme.builder()
            .base("#010101")
            .panel("#020202")
            .selected("#030303")
            .accent("#040404")
            .onAccent("#050505")
            .textPrimary("#060606")
            .textSecondary("#070707");

    assertThatNullPointerException()
        .isThrownBy(
            () -> {
              switch (slot) {
                case "base" -> builder.base(null);
                case "panel" -> builder.panel(null);
                case "selected" -> builder.selected(null);
                case "accent" -> builder.accent(null);
                case "onAccent" -> builder.onAccent(null);
                case "textPrimary" -> builder.textPrimary(null);
                case "textSecondary" -> builder.textSecondary(null);
                default -> throw new IllegalArgumentException(slot);
              }
            })
        .withMessageContaining(slot);
  }
}
