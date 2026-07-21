package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
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
  @DisplayName("Should reject account scope carrying household or profile identity")
  void shouldRejectAccountScopeCarryingHouseholdOrProfileIdentity() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER);

    assertThatThrownBy(identity::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Account scope");
  }

  @Test
  @DisplayName("Should reject household scope carrying profile identity")
  void shouldRejectHouseholdScopeCarryingProfileIdentity() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.HOUSEHOLD)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER)
            .profileId(UUID.randomUUID());

    assertThatThrownBy(identity::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Household scope");
  }

  @Test
  @DisplayName("Should reject profile scope without profile identity")
  void shouldRejectProfileScopeWithoutProfileIdentity() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER);

    assertThatThrownBy(identity::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Profile scope");
  }

  @Test
  @DisplayName("Should reject profile identity without household context")
  void shouldRejectProfileIdentityWithoutHouseholdContext() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .profileId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject profile identity without household role")
  void shouldRejectProfileIdentityWithoutHouseholdRole() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .householdId(UUID.randomUUID())
            .profileId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject playback identity without profile identity")
  void shouldRejectPlaybackIdentityWithoutProfileIdentity() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PLAYBACK)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER)
            .streamSessionId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject playback identity without stream session")
  void shouldRejectPlaybackIdentityWithoutStreamSession() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PLAYBACK)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER)
            .profileId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject non-playback identity carrying a stream session")
  void shouldRejectNonPlaybackIdentityCarryingStreamSession() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .householdId(UUID.randomUUID())
            .householdRole(HouseholdRole.MEMBER)
            .profileId(UUID.randomUUID())
            .streamSessionId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject a token missing the roles claim")
  void shouldRejectTokenMissingRolesClaim() {
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
  @DisplayName("Should reject a token with an empty roles claim")
  void shouldRejectTokenWithEmptyRolesClaim() {
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
  @DisplayName("Should reject building a playback authority for an account-scoped identity")
  void shouldRejectPlaybackAuthorityForAccountScopedIdentity() {
    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.ACCOUNT)
            .build();

    assertThatThrownBy(identity::playbackAuthority).isInstanceOf(ProfileRequiredException.class);
  }
}
