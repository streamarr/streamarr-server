package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Token Crypto Config Tests")
class TokenCryptoConfigTest {

  private final TokenCryptoConfig config = new TokenCryptoConfig();

  @Test
  @DisplayName("Should generate ephemeral key when signing key blank")
  void shouldGenerateEphemeralKeyWhenSigningKeyBlank() {
    var key = config.authSigningKey(propertiesWithKey(""));

    assertThat(key.getEncoded()).hasSize(32);
    assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
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

  private static AuthTokenProperties propertiesWithKey(String signingKey) {
    return AuthTokenProperties.builder()
        .signingKey(signingKey)
        .accessTokenTtl(Duration.ofMinutes(10))
        .refreshTokenTtl(Duration.ofDays(30))
        .rotationGrace(Duration.ofSeconds(30))
        .build();
  }
}
