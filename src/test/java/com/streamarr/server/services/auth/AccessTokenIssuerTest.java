package com.streamarr.server.services.auth;

import static com.streamarr.server.support.TokenTestSupport.decoder;
import static com.streamarr.server.support.TokenTestSupport.tokenProperties;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.fixtures.AccountFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Access Token Issuer Tests")
class AccessTokenIssuerTest {

  private final AuthTokenProperties properties = tokenProperties();
  private final TokenCryptoConfig cryptoConfig = new TokenCryptoConfig();
  private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
  private final AccessTokenIssuer issuer =
      new AccessTokenIssuer(
          cryptoConfig.jwtEncoder(cryptoConfig.tokenSigningKeys(properties)),
          properties,
          Clock.fixed(now, ZoneOffset.UTC));

  @Test
  @DisplayName("Should issue account token without profile or household claims")
  void shouldIssueAccountTokenWithoutProfileOrHouseholdClaims() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();

    var token = issuer.issue(TokenContext.builder().account(account).session(session).build());

    assertThat(token.scope()).isEqualTo(TokenScope.ACCOUNT);
    var decoded = decoder(properties).decode(token.value());
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("account");
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isNull();
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID)).isNull();
  }

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

  @Test
  @DisplayName("Should cap derived token at source expiry without extending a fresh source")
  void shouldCapDerivedTokenAtSourceExpiryWithoutExtendingFreshSource() {
    var account = AccountFixture.defaultAccountBuilder().id(UUID.randomUUID()).build();
    var session = AuthSession.builder().id(UUID.randomUUID()).accountId(account.getId()).build();
    var context = TokenContext.builder().account(account).session(session).build();

    var capped = issuer.issueDerived(context, now.plusSeconds(90));
    var fresh = issuer.issueDerived(context, now.plusSeconds(3600));

    assertThat(capped.expiresAt()).isEqualTo(now.plusSeconds(90));
    assertThat(fresh.expiresAt()).isEqualTo(now.plus(properties.accessTokenTtl()));
  }
}
