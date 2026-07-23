package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Create Auth Session Command Tests")
class CreateAuthSessionCommandTest {

  @Test
  @DisplayName("Should reject a null account id when constructed")
  void shouldRejectNullAccountIdWhenConstructed() {
    assertThatThrownBy(() -> new CreateAuthSessionCommand(null, "device", null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("accountId");
  }

  @Test
  @DisplayName("Should allow absent household and profile selection when constructed")
  void shouldAllowAbsentSelectionWhenConstructed() {
    var command = new CreateAuthSessionCommand(UUID.randomUUID(), "device", null, null);

    assertThat(command.activeHouseholdId()).isNull();
    assertThat(command.activeProfileId()).isNull();
  }
}
