package com.streamarr.server.config.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Slf4j
@Configuration
public class TokenCryptoConfig {

  private static final int MIN_KEY_BYTES = 32;
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  @Bean
  public SecretKey authSigningKey(AuthTokenProperties properties) {
    if (properties.signingKey() == null || properties.signingKey().isBlank()) {
      log.warn(
          "AUTH_TOKEN_SIGNING_KEY is not set; generated an ephemeral signing key. Outstanding"
              + " access and playback tokens are invalidated on restart; sessions recover through"
              + " refresh.");
      var bytes = new byte[MIN_KEY_BYTES];
      new SecureRandom().nextBytes(bytes);
      return new SecretKeySpec(bytes, HMAC_ALGORITHM);
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(properties.signingKey());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("AUTH_TOKEN_SIGNING_KEY must be valid base64.", e);
    }

    if (decoded.length < MIN_KEY_BYTES) {
      throw new IllegalStateException(
          "AUTH_TOKEN_SIGNING_KEY must decode to at least " + MIN_KEY_BYTES + " bytes.");
    }

    return new SecretKeySpec(decoded, HMAC_ALGORITHM);
  }

  @Bean
  public JwtEncoder jwtEncoder(SecretKey authSigningKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(authSigningKey));
  }
}
