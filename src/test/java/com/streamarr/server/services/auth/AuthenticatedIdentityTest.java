package com.streamarr.server.services.auth;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.defaultIdentityBuilder;
import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.ProfileRequiredException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

@Tag("UnitTest")
@DisplayName("Authenticated Identity Tests")
class AuthenticatedIdentityTest {

  @Test
  @DisplayName("Should reject account scope carrying a selected profile when constructed")
  void shouldRejectAccountScopeCarryingSelectedProfileWhenConstructed() {
    var identity = accountScopedBuilder().profileId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should reject profile scope without a selected profile when constructed")
  void shouldRejectProfileScopeWithoutSelectedProfileWhenConstructed() {
    var identity = profileScopedBuilder().profileId(null);

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should require the membership household, role, and context household")
  void shouldRequireMembershipHouseholdRoleAndContextHousehold() {
    assertThatThrownBy(() -> profileScopedBuilder().householdId(null).build())
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> profileScopedBuilder().householdRole(null).build())
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> profileScopedBuilder().contextHouseholdId(null).build())
        .isInstanceOf(NullPointerException.class);
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
    var identity = profileScopedBuilder().streamSessionId(UUID.randomUUID());

    assertThatThrownBy(identity::build).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Should read every signed fact from the token claims")
  void shouldReadEverySignedFactFromTokenClaims() {
    var accountId = UUID.randomUUID();
    var sessionId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    var visitedId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(accountId.toString())
            .claim(TokenClaims.SESSION_ID, sessionId.toString())
            .claim(TokenClaims.SCOPE, "profile")
            .claim(TokenClaims.HOUSEHOLD_ID, householdId.toString())
            .claim(TokenClaims.HOUSEHOLD_ROLE, "ADMIN")
            .claim(TokenClaims.SERVER_ADMIN, true)
            .claim(TokenClaims.CONTEXT_HOUSEHOLD_ID, visitedId.toString())
            .claim(TokenClaims.PROFILE_ID, profileId.toString())
            .build();

    var identity = AuthenticatedIdentity.fromJwt(jwt);

    assertThat(identity.accountId()).isEqualTo(accountId);
    assertThat(identity.authSessionId()).isEqualTo(sessionId);
    assertThat(identity.scope()).isEqualTo(TokenScope.PROFILE);
    assertThat(identity.householdId()).isEqualTo(householdId);
    assertThat(identity.householdRole()).isEqualTo(HouseholdRole.ADMIN);
    assertThat(identity.serverAdmin()).isTrue();
    assertThat(identity.contextHouseholdId()).isEqualTo(visitedId);
    assertThat(identity.profileId()).isEqualTo(profileId);
    assertThat(identity.playbackAuthority().householdId()).isEqualTo(visitedId);
  }

  @Test
  @DisplayName("Should read a missing ServerAdmin claim as not admin")
  void shouldReadMissingServerAdminClaimAsNotAdmin() {
    var householdId = UUID.randomUUID();
    var jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject(UUID.randomUUID().toString())
            .claim(TokenClaims.SESSION_ID, UUID.randomUUID().toString())
            .claim(TokenClaims.SCOPE, "account")
            .claim(TokenClaims.HOUSEHOLD_ID, householdId.toString())
            .claim(TokenClaims.HOUSEHOLD_ROLE, "MEMBER")
            .claim(TokenClaims.CONTEXT_HOUSEHOLD_ID, householdId.toString())
            .build();

    var identity = AuthenticatedIdentity.fromJwt(jwt);

    assertThat(identity.serverAdmin()).isFalse();
    assertThat(identity.scope()).isEqualTo(TokenScope.ACCOUNT);
    assertThat(identity.profileId()).isNull();
  }

  @Test
  @DisplayName("Should reject building a playback authority for an account-scoped identity")
  void shouldRejectPlaybackAuthorityForAccountScopedIdentity() {
    var identity = accountScopedBuilder().build();

    assertThatThrownBy(identity::playbackAuthority).isInstanceOf(ProfileRequiredException.class);
  }
}
