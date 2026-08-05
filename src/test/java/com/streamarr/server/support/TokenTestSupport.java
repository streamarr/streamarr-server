package com.streamarr.server.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.config.security.TokenCryptoConfig;
import java.time.Duration;
import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

public final class TokenTestSupport {

  public static final String TEST_SIGNING_KEY =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQga+ZKCbAcyZIb7k2FE8rMPFtIpTdzX2dR/csZ8k6A95uhRANCAAQawOmVKMDLAOsboxKLb9khGsWyxwcIikucXDCfX18ME5X9/kqSS2vdMnFfZ6KR12U/Sy/EwOwnc82xFAyFdNbe";

  private TokenTestSupport() {}

  public static AuthTokenProperties tokenProperties() {
    return AuthTokenProperties.builder()
        .signingKey(TEST_SIGNING_KEY)
        .verificationKeys(List.of())
        .accessTokenTtl(Duration.ofMinutes(10))
        .refreshTokenTtl(Duration.ofDays(30))
        .rotationGrace(Duration.ofSeconds(30))
        .build();
  }

  public static NimbusJwtDecoder decoder(AuthTokenProperties properties) {
    var keys = new TokenCryptoConfig().tokenSigningKeys(properties);
    var processor = new DefaultJWTProcessor<SecurityContext>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(
            JWSAlgorithm.ES256, new ImmutableJWKSet<>(keys.verificationKeys())));
    processor.setJWTClaimsSetVerifier((claims, context) -> {});
    return new NimbusJwtDecoder(processor);
  }

  public static Jwt decode(String token, AuthTokenProperties properties) {
    return decoder(properties).decode(token);
  }
}
