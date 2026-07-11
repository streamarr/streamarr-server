package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Token Signing Keys Redaction Tests")
class TokenSigningKeysRedactionTest {

  @Test
  @DisplayName("Should not expose private scalar in string representation")
  void shouldNotExposePrivateScalarInStringRepresentation() throws Exception {
    var signingKey = new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
    var keys = new TokenSigningKeys(signingKey, new JWKSet(signingKey.toPublicJWK()));

    assertThat(keys.toString()).doesNotContain(signingKey.getD().toString());
  }
}
