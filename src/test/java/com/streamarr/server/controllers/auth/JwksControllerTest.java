package com.streamarr.server.controllers.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Jwks Controller Tests")
class JwksControllerTest {

  // Checked-in EC P-256 test keys (base64 PKCS#8 private / SPKI public). Never used outside tests.
  private static final String SIGNING_KEY =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg0dbDaE369GEdfm2yb8NJzKVe8oX3KJRNxdjqLH09JxqhRANCAARoDCywOrF0R5XSzhpg2X6g4xQJzuaKLQuiu8W9Lbhk3K6p7hqvBoRWzS4fxMGyF+6DLiOtdo3DWq0kd0Rqy5ye";
  private static final String RETIRED_PUBLIC_KEY =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEGsDplSjAywDrG6MSi2/ZIRrFsscHCIpLnFwwn19fDBOV/f5Kkktr3TJxX2eikddlP0svxMDsJ3PNsRQMhXTW3g==";

  @Test
  @DisplayName("Should serve retired verification key alongside current when rotating")
  void shouldServeRetiredVerificationKeyAlongsideCurrentWhenRotating() {
    var config = new TokenCryptoConfig();
    var keys =
        config.tokenSigningKeys(
            AuthTokenProperties.builder()
                .signingKey(SIGNING_KEY)
                .verificationKeys(List.of(RETIRED_PUBLIC_KEY))
                .accessTokenTtl(Duration.ofMinutes(10))
                .refreshTokenTtl(Duration.ofDays(30))
                .rotationGrace(Duration.ofSeconds(30))
                .build());

    var body = new JwksController(keys).jwks().getBody();

    @SuppressWarnings("unchecked")
    var served = (List<Map<String, Object>>) body.get("keys");
    assertThat(served).hasSize(2);
    assertThat(served)
        .allSatisfy(
            key -> {
              assertThat(key.get("kid")).isNotNull();
              assertThat(key.get("use")).isEqualTo("sig");
              // Signing material never leaves the server, retired or current.
              assertThat(key).doesNotContainKey("d");
            });
    assertThat(served.get(0).get("kid")).isNotEqualTo(served.get(1).get("kid"));
  }
}
