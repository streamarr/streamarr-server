package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Device Authorization Insert Result Tests")
class DeviceAuthorizationInsertResultTest {

  @Test
  @DisplayName("Should reject a negative outstanding count")
  void shouldRejectNegativeOutstandingCount() {
    assertThatThrownBy(() -> new DeviceAuthorizationInsertResult(false, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be negative");
  }

  @Test
  @DisplayName("Should reject a successful insert that leaves no outstanding code")
  void shouldRejectSuccessfulInsertWithNoOutstandingCode() {
    assertThatThrownBy(() -> new DeviceAuthorizationInsertResult(true, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must leave one outstanding code");
  }

  @Test
  @DisplayName("Should accept the boundary outcomes produced by the repository")
  void shouldAcceptBoundaryOutcomesProducedByRepository() {
    assertThat(new DeviceAuthorizationInsertResult(false, 0).inserted()).isFalse();
    assertThat(new DeviceAuthorizationInsertResult(true, 1).inserted()).isTrue();
  }
}
