package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
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
  @DisplayName("Should construct profile identity without household claims")
  void shouldConstructProfileIdentityWithoutHouseholdClaims() {
    var profileId = UUID.randomUUID();

    var identity =
        AuthenticatedIdentity.builder()
            .accountId(UUID.randomUUID())
            .role(AccountRole.USER)
            .authSessionId(UUID.randomUUID())
            .scope(TokenScope.PROFILE)
            .profileId(profileId)
            .build();

    assertThat(identity.profileId()).isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should reject profile claims that do not match token scope")
  void shouldRejectProfileClaimsThatDoNotMatchTokenScope() {
    var accountScopeWithProfile = identityBuilder(TokenScope.ACCOUNT).profileId(UUID.randomUUID());
    var profileScopeWithoutProfile = identityBuilder(TokenScope.PROFILE);

    assertThatThrownBy(accountScopeWithProfile::build).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(profileScopeWithoutProfile::build)
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject playback-only claims on another token scope")
  void shouldRejectPlaybackOnlyClaimsOnAnotherTokenScope() {
    var accountWithHousehold = identityBuilder(TokenScope.ACCOUNT).householdId(UUID.randomUUID());
    var accountWithStream = identityBuilder(TokenScope.ACCOUNT).streamSessionId(UUID.randomUUID());

    assertThatThrownBy(accountWithHousehold::build).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(accountWithStream::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should require every playback identity claim")
  void shouldRequireEveryPlaybackIdentityClaim() {
    var missingHousehold =
        identityBuilder(TokenScope.PLAYBACK)
            .profileId(UUID.randomUUID())
            .streamSessionId(UUID.randomUUID());
    var missingProfile =
        identityBuilder(TokenScope.PLAYBACK)
            .householdId(UUID.randomUUID())
            .streamSessionId(UUID.randomUUID());
    var missingStream =
        identityBuilder(TokenScope.PLAYBACK)
            .householdId(UUID.randomUUID())
            .profileId(UUID.randomUUID());

    assertThatThrownBy(missingHousehold::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("household")
        .hasMessageContaining("profile")
        .hasMessageContaining("stream session");
    assertThatThrownBy(missingProfile::build).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(missingStream::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should build playback authority from complete playback identity")
  void shouldBuildPlaybackAuthorityFromCompletePlaybackIdentity() {
    var householdId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var identity =
        identityBuilder(TokenScope.PLAYBACK)
            .householdId(householdId)
            .profileId(profileId)
            .streamSessionId(UUID.randomUUID())
            .build();

    var authority = identity.playbackAuthority();

    assertThat(authority.householdId()).isEqualTo(householdId);
    assertThat(authority.profileId()).isEqualTo(profileId);
  }

  @Test
  @DisplayName("Should require playback profile and household for playback authority")
  void shouldRequirePlaybackProfileAndHouseholdForPlaybackAuthority() {
    var accountIdentity = identityBuilder(TokenScope.ACCOUNT).build();

    assertThatThrownBy(accountIdentity::playbackAuthority)
        .isInstanceOf(ProfileRequiredException.class);
  }

  @Test
  @DisplayName("Should reject JWT without account role")
  void shouldRejectJwtWithoutAccountRole() {
    var missingRoles = jwtBuilder().build();
    var emptyRoles = jwtBuilder().claim(TokenClaims.ROLES, List.of()).build();

    assertThatThrownBy(() -> AuthenticatedIdentity.fromJwt(missingRoles))
        .isInstanceOf(AuthenticationRequiredException.class);
    assertThatThrownBy(() -> AuthenticatedIdentity.fromJwt(emptyRoles))
        .isInstanceOf(AuthenticationRequiredException.class);
  }

  private AuthenticatedIdentity.AuthenticatedIdentityBuilder identityBuilder(TokenScope scope) {
    return AuthenticatedIdentity.builder()
        .accountId(UUID.randomUUID())
        .role(AccountRole.USER)
        .authSessionId(UUID.randomUUID())
        .scope(scope);
  }

  private Jwt.Builder jwtBuilder() {
    return Jwt.withTokenValue("test-token")
        .header("alg", "none")
        .subject(UUID.randomUUID().toString())
        .claim(TokenClaims.SESSION_ID, UUID.randomUUID().toString())
        .claim(TokenClaims.SCOPE, TokenScope.ACCOUNT.name());
  }
}
