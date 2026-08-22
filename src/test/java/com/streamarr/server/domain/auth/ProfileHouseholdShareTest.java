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
  @DisplayName("Should expose Optional empty when the invalidation reason is absent")
  void shouldExposeOptionalEmptyWhenInvalidationReasonIsAbsent() {
    var share = ProfileHouseholdShare.builder().build();

    assertThat(share.getInvalidationReason()).isEqualTo(Optional.empty());
  }
}
