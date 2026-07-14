package com.streamarr.server.config.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.streamarr.server.services.auth.StrictJwtExpiryValidator;
import com.streamarr.server.services.auth.TokenIdentityValidator;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * ES256 token crypto. Only the media server holds the EC private key; verifiers — including the
 * planned transcode service, which scales horizontally and must never hold a minting secret — need
 * only the public keys. Every key carries its RFC 7638 thumbprint as kid, so signing-key rotation
 * is two-phase (full runbook in architecture.adoc): first prepublish the incoming public key
 * through auth.token.verification-keys and restart, so JWKS advertises it at least one
 * Cache-Control window before it signs; only then configure the new private key, move the old
 * public key into verification-keys, and restart. Skipping the prepublish step lets a shared JWKS
 * cache answer an unknown-kid refetch with the pre-rotation key set and reject newly signed tokens.
 */
@Slf4j
@Configuration
public class TokenCryptoConfig {

  @Bean
  public TokenSigningKeys tokenSigningKeys(AuthTokenProperties properties) {
    var signingKey = loadOrGenerateSigningKey(properties.signingKey());

    var verificationKeys = new ArrayList<JWK>();
    verificationKeys.add(signingKey.toPublicJWK());
    for (var retired : retiredKeys(properties)) {
      verificationKeys.add(retiredPublicKey(retired));
    }

    return new TokenSigningKeys(signingKey, new JWKSet(verificationKeys));
  }

  @Bean
  public JwtEncoder jwtEncoder(TokenSigningKeys keys) {
    return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(keys.signingKey())));
  }

  /**
   * Identity validation runs inside the decoder, so malformed tokens never become an
   * Authentication. The key selector is pinned to ES256: HMAC or unsigned tokens never reach
   * validation.
   */
  @Bean
  public JwtDecoder jwtDecoder(
      TokenSigningKeys keys,
      TokenIdentityValidator identityValidator,
      AuthTokenProperties properties) {
    var processor = new DefaultJWTProcessor<SecurityContext>();
    processor.setJWSKeySelector(
        new JWSVerificationKeySelector<>(
            JWSAlgorithm.ES256, new ImmutableJWKSet<>(keys.verificationKeys())));
    // Claims are validated by Spring's validators below — Nimbus-level expiry checks would
    // bypass JwtValidationException and break the EXPIRED_TOKEN signal at the HTTP layer.
    processor.setJWTClaimsSetVerifier((claims, context) -> {});

    var decoder = new NimbusJwtDecoder(processor);
    // The default chain keeps issuer and nbf validation; the strict validator makes exp
    // mandatory with zero leeway, overriding the default 60s-skew expiry tolerance.
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(properties.issuer()),
            // RFC 9068 §4: the resource server MUST validate that aud contains an identifier
            // it expects for itself.
            new JwtClaimValidator<List<String>>(
                JwtClaimNames.AUD,
                audience -> audience != null && audience.contains(properties.audience())),
            new StrictJwtExpiryValidator(Clock.systemUTC()),
            identityValidator));
    return decoder;
  }

  private ECKey loadOrGenerateSigningKey(String configured) {
    if (configured == null || configured.isBlank()) {
      log.warn(
          "AUTH_TOKEN_SIGNING_KEY is not set; generated an ephemeral signing key pair. Outstanding"
              + " access and playback tokens are invalidated on restart; sessions recover through"
              + " refresh.");
      return generateEphemeralKey();
    }

    var privateKey = parsePrivateKey(decode(configured, "AUTH_TOKEN_SIGNING_KEY"));
    requireP256(privateKey.getParams(), "AUTH_TOKEN_SIGNING_KEY");

    try {
      return new ECKey.Builder(Curve.P_256, derivePublicKey(privateKey))
          .privateKey(privateKey)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.ES256)
          .keyIDFromThumbprint()
          .build();
    } catch (JOSEException e) {
      throw new IllegalStateException("Unable to compute the signing key thumbprint.", e);
    }
  }

  private ECKey generateEphemeralKey() {
    try {
      return new ECKeyGenerator(Curve.P_256)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.ES256)
          .keyIDFromThumbprint(true)
          .generate();
    } catch (JOSEException e) {
      throw new IllegalStateException("Unable to generate an ephemeral EC key pair.", e);
    }
  }

  private static List<String> retiredKeys(AuthTokenProperties properties) {
    if (properties.verificationKeys() == null) {
      return List.of();
    }
    return properties.verificationKeys().stream().filter(key -> !key.isBlank()).toList();
  }

  private static JWK retiredPublicKey(String encoded) {
    ECPublicKey publicKey;
    try {
      publicKey =
          (ECPublicKey)
              keyFactory()
                  .generatePublic(
                      new X509EncodedKeySpec(decode(encoded, "AUTH_TOKEN_VERIFICATION_KEYS")));
    } catch (InvalidKeySpecException | ClassCastException e) {
      throw new IllegalStateException(
          "AUTH_TOKEN_VERIFICATION_KEYS entries must be X.509 (SPKI) EC public keys.", e);
    }
    requireP256(publicKey.getParams(), "AUTH_TOKEN_VERIFICATION_KEYS");

    try {
      return new ECKey.Builder(Curve.P_256, publicKey)
          .keyUse(KeyUse.SIGNATURE)
          .algorithm(JWSAlgorithm.ES256)
          .keyIDFromThumbprint()
          .build();
    } catch (JOSEException e) {
      throw new IllegalStateException("Unable to compute a verification key thumbprint.", e);
    }
  }

  private static ECPrivateKey parsePrivateKey(byte[] der) {
    try {
      return (ECPrivateKey) keyFactory().generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (InvalidKeySpecException | ClassCastException e) {
      throw new IllegalStateException("AUTH_TOKEN_SIGNING_KEY must be a PKCS#8 EC private key.", e);
    }
  }

  /** PKCS#8 EC keys need not embed the public point, so derive it: Q = d * G. */
  private static ECPublicKey derivePublicKey(ECPrivateKey privateKey) {
    var curve = ECNamedCurveTable.getParameterSpec("secp256r1");
    var q = curve.getG().multiply(privateKey.getS()).normalize();
    var point = new ECPoint(q.getAffineXCoord().toBigInteger(), q.getAffineYCoord().toBigInteger());
    try {
      return (ECPublicKey)
          keyFactory().generatePublic(new ECPublicKeySpec(point, privateKey.getParams()));
    } catch (InvalidKeySpecException e) {
      throw new IllegalStateException("Unable to derive the EC public key.", e);
    }
  }

  private static void requireP256(ECParameterSpec params, String source) {
    if (!Curve.P_256.equals(Curve.forECParameterSpec(params))) {
      throw new IllegalStateException(source + " must use curve P-256.");
    }
  }

  private static byte[] decode(String value, String source) {
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(source + " must be valid base64.", e);
    }
  }

  private static KeyFactory keyFactory() {
    try {
      return KeyFactory.getInstance("EC");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("EC key factory is required but unavailable.", e);
    }
  }
}
