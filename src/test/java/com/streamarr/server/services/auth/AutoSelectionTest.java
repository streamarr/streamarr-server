package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Auto Selection Tests")
class AutoSelectionTest {

  @Test
  @DisplayName("Should reject a profile when its owning household is absent")
  void shouldRejectProfileWhenOwningHouseholdAbsent() {
    var profileId = UUID.randomUUID();

    assertThatThrownBy(() -> new AutoSelection(null, profileId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("household");
  }
}
