package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.services.auth.TokenClaims;
import com.streamarr.server.services.auth.TokenIdentityValidator;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.auth.TokenVersionCache;
import com.streamarr.server.services.auth.TokenVersionValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

@Tag("UnitTest")
@DisplayName("Token Crypto Config Tests")
class TokenCryptoConfigTest {

  private static final String TEST_KEY_BASE64 = "dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=";

  private final TokenCryptoConfig config = new TokenCryptoConfig();

  @Test
  @DisplayName("Should generate ephemeral key when signing key blank")
  void shouldGenerateEphemeralKeyWhenSigningKeyBlank() {
    var key = config.authSigningKey(propertiesWithKey(""));

    assertThat(key.getEncoded()).hasSize(32);
    assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
  }

  @Test
  @DisplayName("Should generate ephemeral key when signing key missing")
  void shouldGenerateEphemeralKeyWhenSigningKeyMissing() {
    var key = config.authSigningKey(propertiesWithKey(null));

    assertThat(key.getEncoded()).hasSize(32);
    assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
  }

  @Test
  @DisplayName("Should generate distinct ephemeral keys when key not configured")
  void shouldGenerateDistinctEphemeralKeysWhenKeyNotConfigured() {
    var first = config.authSigningKey(propertiesWithKey(null));
    var second = config.authSigningKey(propertiesWithKey(""));

    assertThat(first.getEncoded()).isNotEqualTo(second.getEncoded());
  }

  @Test
  @DisplayName("Should use configured key when signing key valid")
  void shouldUseConfiguredKeyWhenSigningKeyValid() {
    var keyBytes = "test-signing-key-32-bytes-long!!".getBytes();

    var key =
        config.authSigningKey(propertiesWithKey(Base64.getEncoder().encodeToString(keyBytes)));

    assertThat(key.getEncoded()).isEqualTo(keyBytes);
  }

  @Test
  @DisplayName("Should fail fast when signing key not base64")
  void shouldFailFastWhenSigningKeyNotBase64() {
    var properties = propertiesWithKey("not-valid-base64!!!");

    assertThatThrownBy(() -> config.authSigningKey(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base64");
  }

  @Test
  @DisplayName("Should fail fast when signing key too short")
  void shouldFailFastWhenSigningKeyTooShort() {
    var properties = propertiesWithKey(Base64.getEncoder().encodeToString("too-short".getBytes()));

    assertThatThrownBy(() -> config.authSigningKey(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("32 bytes");
  }

  @Test
  @DisplayName("Should reject token when issuer is foreign")
  void shouldRejectTokenFromForeignIssuer() {
    var properties = propertiesWithKey(TEST_KEY_BASE64);
    var signingKey = config.authSigningKey(properties);
    var sessionId = UUID.randomUUID();
    var accountId = UUID.randomUUID();
    var reader = new FakeVersionCounterReader();
    reader.sessionVersions.put(sessionId, 0L);
    var decoder =
        config.jwtDecoder(
            signingKey,
            new TokenIdentityValidator(),
            new TokenVersionValidator(new TokenVersionCache(reader)));
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer("foreign-issuer")
            .subject(accountId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim(TokenClaims.ROLE, AccountRole.USER.name())
            .claim(TokenClaims.SESSION_ID, sessionId.toString())
            .claim(TokenClaims.SESSION_VERSION, 0L)
            .claim(TokenClaims.SCOPE, TokenScope.ACCOUNT.claimValue())
            .build();
    var token =
        config
            .jwtEncoder(signingKey)
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();

    assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtValidationException.class);
  }

  private static AuthTokenProperties propertiesWithKey(String signingKey) {
    return AuthTokenProperties.builder()
        .signingKey(signingKey)
        .accessTokenTtl(Duration.ofMinutes(10))
        .refreshTokenTtl(Duration.ofDays(30))
        .rotationGrace(Duration.ofSeconds(30))
        .build();
  }
}
