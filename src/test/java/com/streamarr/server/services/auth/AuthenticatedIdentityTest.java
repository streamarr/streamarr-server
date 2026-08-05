package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Authenticated Identity Tests")
class AuthenticatedIdentityTest {

  @Test
  @DisplayName(
      "Should reject account scope carrying household or profile identity when constructed")
  void shouldRejectAccountScopeCarryingHouseholdOrProfileIdentityWhenConstructed() {
    var identity = defaultIdentityBuilder().scope(TokenScope.ACCOUNT).streamSessionId(null);

    assertThatThrownBy(identity::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Account scope");
  }

  @Test
  @DisplayName("Should reject household scope carrying profile identity when constructed")
  void shouldRejectHouseholdScopeCarryingProfileIdentityWhenConstructed() {
    var identity = defaultIdentityBuilder().scope(TokenScope.HOUSEHOLD).streamSessionId(null);

    assertThatThrownBy(identity::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Household scope");
  }

  @Test
  @DisplayName("Should reject profile scope without profile identity when constructed")
  void shouldRejectProfileScopeWithoutProfileIdentityWhenConstructed() {
    var identity =
        defaultIdentityBuilder().scope(TokenScope.PROFILE).profileId(null).streamSessionId(null);

    assertThatThrownBy(identity::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Profile scope");
  }

  @Test
  @DisplayName("Should reject profile identity without household context when constructed")
  void shouldRejectProfileIdentityWithoutHouseholdContextWhenConstructed() {
    var identity =
        defaultIdentityBuilder().scope(TokenScope.PROFILE).householdId(null).streamSessionId(null);

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject profile identity without household role when constructed")
  void shouldRejectProfileIdentityWithoutHouseholdRoleWhenConstructed() {
    var identity =
        defaultIdentityBuilder()
            .scope(TokenScope.PROFILE)
            .householdRole(null)
            .streamSessionId(null);

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject playback identity without profile identity when constructed")
  void shouldRejectPlaybackIdentityWithoutProfileIdentityWhenConstructed() {
    var identity = defaultIdentityBuilder().profileId(null);

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject playback identity without stream session when constructed")
  void shouldRejectPlaybackIdentityWithoutStreamSessionWhenConstructed() {
    var identity = defaultIdentityBuilder().streamSessionId(null);

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject non-playback identity carrying a stream session when constructed")
  void shouldRejectNonPlaybackIdentityCarryingStreamSessionWhenConstructed() {
    var identity =
        defaultIdentityBuilder().scope(TokenScope.PROFILE).streamSessionId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject a token missing the roles claim when constructed")
  void shouldRejectTokenMissingRolesClaimWhenConstructed() {
    var jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(UUID.randomUUID().toString())
            .claim(TokenClaims.SESSION_ID, UUID.randomUUID().toString())
            .build();

    assertThatThrownBy(() -> AuthenticatedIdentity.fromJwt(jwt))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName("Should reject a token with an empty roles claim when constructed")
  void shouldRejectTokenWithEmptyRolesClaimWhenConstructed() {
    var jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(UUID.randomUUID().toString())
            .claim(TokenClaims.ROLES, List.of())
            .build();

    assertThatThrownBy(() -> AuthenticatedIdentity.fromJwt(jwt))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  @Test
  @DisplayName(
      "Should reject building a playback authority for an account-scoped identity when constructed")
  void shouldRejectPlaybackAuthorityForAccountScopedIdentityWhenConstructed() {
    var identity =
        defaultIdentityBuilder()
            .scope(TokenScope.ACCOUNT)
            .householdId(null)
            .householdRole(null)
            .profileId(null)
            .streamSessionId(null)
            .build();

    assertThatThrownBy(identity::playbackAuthority).isInstanceOf(ProfileRequiredException.class);
  }
}
