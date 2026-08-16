package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Portable Identity Input Validation Tests")
class PortableIdentityInputValidationTest {

  @Test
  @DisplayName("Should reject a negative content ceiling when creating a profile")
  void shouldRejectNegativeContentCeilingWhenCreatingProfile() {
    assertThatThrownBy(
            () ->
                CreatePortableProfileCommand.builder()
                    .authority(accountIdentityBuilder().build())
                    .name("Invalid Ceiling")
                    .kind(ProfileKind.KID)
                    .maximumAllowedRatingAge(-1)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximumAllowedRatingAge");
  }

  @Test
  @DisplayName("Should reject a negative content ceiling when updating a profile")
  void shouldRejectNegativeContentCeilingWhenUpdatingProfile() {
    assertThatThrownBy(
            () ->
                SetProfileContentCeilingCommand.builder()
                    .actingAccountId(UUID.randomUUID())
                    .profileId(UUID.randomUUID())
                    .maximumAllowedRatingAge(-1)
                    .build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maximumAllowedRatingAge");
  }
}
