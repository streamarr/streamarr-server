package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Profile Household Share Tests")
class ProfileHouseholdShareTest {

  @Test
  @DisplayName("Should expose an absent invalidation reason as Optional empty")
  void shouldExposeAbsentInvalidationReasonAsOptionalEmpty() {
    var share = ProfileHouseholdShare.builder().build();

    assertThat(share.getInvalidationReason()).isEqualTo(Optional.empty());
  }
}
