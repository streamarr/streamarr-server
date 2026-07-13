package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.support.AuthTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@Tag("IntegrationTest")
@DisplayName("Access Token Authority Window Integration Tests")
class MembershipTokenRevocationIT extends AbstractIntegrationTest {

  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private JwtDecoder jwtDecoder;
  @Autowired private HouseholdMembershipRepository membershipRepository;

  @Test
  @DisplayName("Should accept an issued access token until expiry when membership is revoked")
  void shouldAcceptIssuedAccessTokenUntilExpiryWhenMembershipRevoked() {
    var identity = authTestSupport.createIdentity();

    try {
      var accountId = identity.account().getId();
      var householdId = identity.household().getId();
      var rawToken = authTestSupport.householdBearer(identity);
      var decoded = jwtDecoder.decode(rawToken);

      membershipRepository.revokeMembership(accountId, householdId).orElseThrow();

      assertThat(jwtDecoder.decode(rawToken).getSubject()).isEqualTo(decoded.getSubject());
    } finally {
      authTestSupport.deleteIdentity(identity);
    }
  }
}
