package com.streamarr.server.services.auth;

import static com.streamarr.server.support.TokenTestSupport.decoder;
import static com.streamarr.server.support.TokenTestSupport.tokenProperties;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Access Token Issuer Tests")
class AccessTokenIssuerTest {

  private final AuthTokenProperties properties = tokenProperties();
  private final TokenCryptoConfig cryptoConfig = new TokenCryptoConfig();
  private final AccessTokenIssuer issuer =
      new AccessTokenIssuer(
          cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)),
          properties,
          Clock.systemUTC());

  @Test
  @DisplayName("Should issue profile token without household claims")
  void shouldIssueProfileTokenWithoutHouseholdClaims() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();
    var profileId = UUID.randomUUID();

    var token =
        issuer.issue(
            TokenContext.builder().account(account).session(session).profileId(profileId).build());

    assertThat(token.scope()).isEqualTo(TokenScope.PROFILE);
    var decoded = decoder(properties).decode(token.value());
    assertThat(decoded.getSubject()).isEqualTo(account.getId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SESSION_ID))
        .isEqualTo(session.getId().toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("profile");
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isEqualTo(profileId.toString());
    assertThat(decoded.getClaimAsString("hh")).isNull();
    assertThat(decoded.getClaimAsString("hr")).isNull();
  }
}
