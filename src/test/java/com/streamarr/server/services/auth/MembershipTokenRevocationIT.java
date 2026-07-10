package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.HouseholdMembership;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.support.AuthTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;

@Tag("IntegrationTest")
@DisplayName("Membership Token Revocation Integration Tests")
class MembershipTokenRevocationIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private TokenVersionCache tokenVersionCache;
  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Test
  @DisplayName("Should reject warmed household token when membership revoked and regranted")
  void shouldRejectWarmedHouseholdTokenWhenMembershipRevokedAndRegranted() {
    var identity = authTestSupport.createIdentity();

    try {
      var accountId = identity.account().getId();
      var householdId = identity.household().getId();
      var rawToken = authTestSupport.householdBearer(identity);
      var decoded = jwtDecoder.decode(rawToken);
      var embeddedVersion = decoded.<Long>getClaim(TokenClaims.MEMBERSHIP_VERSION);
      assertThat(tokenVersionCache.membershipVersion(accountId, householdId))
          .contains(embeddedVersion);

      var revoked = membershipRepository.revokeMembership(accountId, householdId).orElseThrow();
      var regranted =
          membershipRepository.grantMembership(
              HouseholdMembership.builder()
                  .accountId(accountId)
                  .householdId(householdId)
                  .householdRole(HouseholdRole.OWNER)
                  .build());

      assertThat(regranted.version()).isGreaterThan(revoked.version());
      assertThatThrownBy(() -> jwtDecoder.decode(rawToken))
          .isInstanceOf(JwtValidationException.class);
      assertThat(tokenVersionCache.membershipVersion(accountId, householdId))
          .contains(regranted.version());
    } finally {
      authTestSupport.deleteIdentity(identity);
    }
  }
}
