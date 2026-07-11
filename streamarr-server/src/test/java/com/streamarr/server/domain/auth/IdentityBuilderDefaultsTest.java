package com.streamarr.server.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Identity Builder Defaults Tests")
class IdentityBuilderDefaultsTest {

  @Test
  @DisplayName("Should enable account when builder omits enabled")
  void shouldEnableAccountWhenBuilderOmitsEnabled() {
    var account = UserAccount.builder().build();

    assertThat(account.isEnabled()).isTrue();
  }

  @Test
  @DisplayName("Should default rating region to US when builder omits region")
  void shouldDefaultRatingRegionToUsWhenBuilderOmitsRegion() {
    var household = Household.builder().build();

    assertThat(household.getDefaultRatingRegion()).isEqualTo("US");
  }
}
