package com.streamarr.server.services.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.exceptions.ProfileRequiredException;
import com.streamarr.server.fakes.FakeVersionCounterReader;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

@Tag("UnitTest")
@DisplayName("Playback Token Issuer Tests")
class PlaybackTokenIssuerTest {

  private static final String TEST_KEY_BASE64 = "dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=";

  private final AuthTokenProperties properties =
      AuthTokenProperties.builder()
          .signingKey(TEST_KEY_BASE64)
          .accessTokenTtl(Duration.ofMinutes(10))
          .refreshTokenTtl(Duration.ofDays(30))
          .rotationGrace(Duration.ofSeconds(30))
          .build();

  private final FakeVersionCounterReader reader = new FakeVersionCounterReader();
  private final TokenCryptoConfig cryptoConfig = new TokenCryptoConfig();

  private final PlaybackTokenIssuer issuer =
      new PlaybackTokenIssuer(
          cryptoConfig.jwtEncoder(cryptoConfig.authSigningKey(properties)),
          Clock.systemUTC(),
          new TokenVersionCache(reader));

  private final UUID accountId = UUID.randomUUID();
  private final UUID sessionId = UUID.randomUUID();
  private final UUID householdId = UUID.randomUUID();
  private final UUID profileId = UUID.randomUUID();

  @Test
  @DisplayName("Should include identity and version claims when issuing")
  void shouldIncludeIdentityAndVersionClaimsWhenIssuing() {
    reader.sessionVersions.put(sessionId, 2L);
    reader.membershipVersions.put(accountId + ":" + householdId, 3L);
    reader.profilePolicyVersions.put(profileId, 4L);
    var streamSessionId = UUID.randomUUID();

    var token = issuer.issue(profileIdentity(), streamSessionId, Duration.ofHours(24));

    assertThat(token.scope()).isEqualTo(TokenScope.PLAYBACK);
    var decoded = decode(token.value());
    assertThat(decoded.getSubject()).isEqualTo(accountId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.SESSION_ID)).isEqualTo(sessionId.toString());
    assertThat(decoded.<Long>getClaim(TokenClaims.SESSION_VERSION)).isEqualTo(2L);
    assertThat(decoded.getClaimAsString(TokenClaims.SCOPE)).isEqualTo("playback");
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ID))
        .isEqualTo(householdId.toString());
    assertThat(decoded.getClaimAsString(TokenClaims.HOUSEHOLD_ROLE)).isEqualTo("MEMBER");
    assertThat(decoded.<Long>getClaim(TokenClaims.MEMBERSHIP_VERSION)).isEqualTo(3L);
    assertThat(decoded.getClaimAsString(TokenClaims.PROFILE_ID)).isEqualTo(profileId.toString());
    assertThat(decoded.<Long>getClaim(TokenClaims.POLICY_VERSION)).isEqualTo(4L);
    assertThat(decoded.getClaimAsString(TokenClaims.STREAM_SESSION))
        .isEqualTo(streamSessionId.toString());
    assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
        .isEqualTo(Duration.ofHours(24));
  }

  @Test
  @DisplayName("Should reject issuance when identity has no profile")
  void shouldRejectIssuanceWhenIdentityHasNoProfile() {
    var accountScoped =
        AuthenticatedIdentity.builder()
            .accountId(accountId)
            .role(AccountRole.USER)
            .sessionId(sessionId)
            .scope(TokenScope.ACCOUNT)
            .build();
    var streamSessionId = UUID.randomUUID();
    var ttl = Duration.ofHours(1);

    assertThatThrownBy(() -> issuer.issue(accountScoped, streamSessionId, ttl))
        .isInstanceOf(ProfileRequiredException.class);
  }

  private AuthenticatedIdentity profileIdentity() {
    return AuthenticatedIdentity.builder()
        .accountId(accountId)
        .role(AccountRole.USER)
        .sessionId(sessionId)
        .scope(TokenScope.PROFILE)
        .householdId(householdId)
        .householdRole(HouseholdRole.MEMBER)
        .profileId(profileId)
        .build();
  }

  private org.springframework.security.oauth2.jwt.Jwt decode(String token) {
    var keyBytes = java.util.Base64.getDecoder().decode(TEST_KEY_BASE64);
    return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
        .macAlgorithm(MacAlgorithm.HS256)
        .build()
        .decode(token);
  }
}
