package com.streamarr.server.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.fakes.FakeVersionCounterReader;
import com.streamarr.server.services.auth.TokenClaims;
import com.streamarr.server.services.auth.TokenContract;
import com.streamarr.server.services.auth.TokenIdentityValidator;
import com.streamarr.server.services.auth.TokenScope;
import com.streamarr.server.services.auth.TokenVersionCache;
import com.streamarr.server.services.auth.TokenVersionValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

@Tag("UnitTest")
@DisplayName("Token Crypto Config Tests")
class TokenCryptoConfigTest {

  // Checked-in EC P-256 test keys (base64 PKCS#8 private / SPKI public). Never used outside
  // tests.
  private static final String KEY_A =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQga+ZKCbAcyZIb7k2FE8rMPFtIpTdzX2dR/csZ8k6A95uhRANCAAQawOmVKMDLAOsboxKLb9khGsWyxwcIikucXDCfX18ME5X9/kqSS2vdMnFfZ6KR12U/Sy/EwOwnc82xFAyFdNbe";
  private static final String KEY_A_PUBLIC =
      "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEGsDplSjAywDrG6MSi2/ZIRrFsscHCIpLnFwwn19fDBOV/f5Kkktr3TJxX2eikddlP0svxMDsJ3PNsRQMhXTW3g==";
  private static final String KEY_B =
      "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg0dbDaE369GEdfm2yb8NJzKVe8oX3KJRNxdjqLH09JxqhRANCAARoDCywOrF0R5XSzhpg2X6g4xQJzuaKLQuiu8W9Lbhk3K6p7hqvBoRWzS4fxMGyF+6DLiOtdo3DWq0kd0Rqy5ye";
  private static final String KEY_P384 =
      "MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDC4PSzNh3Os1nWuK40T0ft+WMts6aH/b4b2nDLhwhZ6Vy0kV5QpNC+p5IZVWtjFSkqhZANiAATncoQEvs0zcf+wXXsukA+FRFdhcbdukgSeGxoSRB0VDzJvube5FA7FrT3bLtnSAFIYG6BX+y/gh2gndOhFwN164NiGz/Q4ZDVBPrBrB33avKjn5NbuAHWlyCHaqMTSA4U=";

  private final TokenCryptoConfig config = new TokenCryptoConfig();

  private final FakeVersionCounterReader reader = new FakeVersionCounterReader();
  private final UUID sessionId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();

  @Test
  @DisplayName("Should sign with ES256 and key id when encoding")
  void shouldSignWithEs256AndKeyIdWhenEncoding() throws Exception {
    var keys = config.tokenSigningKeys(properties(KEY_A, List.of()));

    var token = mint(config.jwtEncoder(keys));

    var parsed = SignedJWT.parse(token);
    assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
    assertThat(parsed.getHeader().getKeyID()).isEqualTo(keys.signingKey().getKeyID());

    var decoded = decoder(keys).decode(token);
    assertThat(decoded.getSubject()).isEqualTo(accountId.toString());
  }

  @Test
  @DisplayName("Should reject ES256 token when signature bytes are tampered")
  void shouldRejectEs256TokenWhenSignatureBytesAreTampered() {
    var keys = config.tokenSigningKeys(properties(KEY_A, List.of()));
    var token = mint(config.jwtEncoder(keys));
    var jwtDecoder = decoder(keys);
    assertThat(jwtDecoder.decode(token).getSubject()).isEqualTo(accountId.toString());

    var tamperedToken = tamperSignature(token);

    assertThatThrownBy(() -> jwtDecoder.decode(tamperedToken)).isInstanceOf(BadJwtException.class);
  }

  @Test
  @DisplayName("Should generate ephemeral key pair when signing key blank")
  void shouldGenerateEphemeralKeyPairWhenSigningKeyBlank() {
    var keys = config.tokenSigningKeys(properties("", List.of()));

    assertThat(keys.signingKey().getCurve()).isEqualTo(Curve.P_256);
    assertThat(keys.signingKey().getKeyID()).isNotBlank();

    var token = mint(config.jwtEncoder(keys));
    assertThat(decoder(keys).decode(token).getSubject()).isEqualTo(accountId.toString());
  }

  @Test
  @DisplayName("Should generate ephemeral key pair when signing key missing")
  void shouldGenerateEphemeralKeyPairWhenSigningKeyMissing() {
    var keys = config.tokenSigningKeys(properties(null, List.of()));

    assertThat(keys.signingKey().getCurve()).isEqualTo(Curve.P_256);
    assertThat(keys.signingKey().getKeyID()).isNotBlank();

    var token = mint(config.jwtEncoder(keys));
    assertThat(decoder(keys).decode(token).getSubject()).isEqualTo(accountId.toString());
  }

  @Test
  @DisplayName("Should generate distinct ephemeral key pairs when key not configured")
  void shouldGenerateDistinctEphemeralKeyPairsWhenKeyNotConfigured() {
    var first = config.tokenSigningKeys(properties(null, List.of()));
    var second = config.tokenSigningKeys(properties("", List.of()));

    assertThat(first.signingKey().getKeyID()).isNotEqualTo(second.signingKey().getKeyID());
  }

  @Test
  @DisplayName("Should verify token signed with retired key")
  void shouldVerifyTokenSignedWithRetiredKey() {
    var retiredKeys = config.tokenSigningKeys(properties(KEY_A, List.of()));
    var token = mint(config.jwtEncoder(retiredKeys));

    var rotatedKeys = config.tokenSigningKeys(properties(KEY_B, List.of(KEY_A_PUBLIC)));

    var decoded = decoder(rotatedKeys).decode(token);
    assertThat(decoded.getSubject()).isEqualTo(accountId.toString());
  }

  @Test
  @DisplayName("Should verify with current key when verification keys missing")
  void shouldVerifyWithCurrentKeyWhenVerificationKeysMissing() {
    var keys = config.tokenSigningKeys(properties(KEY_A, null));

    assertThat(keys.verificationKeys().getKeys()).hasSize(1);

    var token = mint(config.jwtEncoder(keys));
    assertThat(decoder(keys).decode(token).getSubject()).isEqualTo(accountId.toString());
  }

  @Test
  @DisplayName("Should reject token from unknown key")
  void shouldRejectTokenFromUnknownKey() {
    var foreignKeys = config.tokenSigningKeys(properties(KEY_B, List.of()));
    var token = mint(config.jwtEncoder(foreignKeys));

    var jwtDecoder = decoder(config.tokenSigningKeys(properties(KEY_A, List.of())));

    assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(BadJwtException.class);
  }

  @Test
  @DisplayName("Should reject token signed with HMAC")
  void shouldRejectTokenSignedWithHmac() throws Exception {
    var hmacToken =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .subject(accountId.toString())
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim(TokenClaims.SESSION_ID, sessionId.toString())
                .claim(TokenClaims.SESSION_VERSION, 0L)
                .build());
    hmacToken.sign(new MACSigner(new byte[32]));
    var token = hmacToken.serialize();

    var jwtDecoder = decoder(config.tokenSigningKeys(properties(KEY_A, List.of())));

    assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(BadJwtException.class);
  }

  @Test
  @DisplayName("Should reject unsigned token")
  void shouldRejectUnsignedToken() {
    var unsignedToken =
        new PlainJWT(
                new JWTClaimsSet.Builder()
                    .subject(accountId.toString())
                    .issueTime(Date.from(Instant.now()))
                    .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                    .claim(TokenClaims.SESSION_ID, sessionId.toString())
                    .claim(TokenClaims.SESSION_VERSION, 0L)
                    .build())
            .serialize();

    var jwtDecoder = decoder(config.tokenSigningKeys(properties(KEY_A, List.of())));

    assertThatThrownBy(() -> jwtDecoder.decode(unsignedToken)).isInstanceOf(BadJwtException.class);
  }

  @Test
  @DisplayName("Should reject token when issuer is foreign")
  void shouldRejectTokenFromForeignIssuer() {
    var keys = config.tokenSigningKeys(properties(KEY_A, List.of()));
    var token = mint(config.jwtEncoder(keys), "foreign-issuer");
    var jwtDecoder = decoder(keys);

    assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(JwtValidationException.class);
  }

  @Test
  @DisplayName("Should fail fast when signing key not base64")
  void shouldFailFastWhenSigningKeyNotBase64() {
    var properties = properties("not-valid-base64!!!", List.of());

    assertThatThrownBy(() -> config.tokenSigningKeys(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("base64");
  }

  @Test
  @DisplayName("Should fail fast when signing key not EC private key")
  void shouldFailFastWhenSigningKeyNotEcPrivateKey() {
    var garbage = Base64.getEncoder().encodeToString("definitely-not-a-pkcs8-ec-key".getBytes());
    var properties = properties(garbage, List.of());

    assertThatThrownBy(() -> config.tokenSigningKeys(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("EC private key");
  }

  @Test
  @DisplayName("Should fail fast when curve not P-256")
  void shouldFailFastWhenCurveNotP256() {
    var properties = properties(KEY_P384, List.of());

    assertThatThrownBy(() -> config.tokenSigningKeys(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("P-256");
  }

  @Test
  @DisplayName("Should fail fast when retired key not base64")
  void shouldFailFastWhenRetiredKeyNotBase64() {
    var properties = properties(KEY_A, List.of("not-valid-base64!!!"));

    assertThatThrownBy(() -> config.tokenSigningKeys(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AUTH_TOKEN_VERIFICATION_KEYS must be valid base64");
  }

  @Test
  @DisplayName("Should fail fast when retired key not EC public key")
  void shouldFailFastWhenRetiredKeyNotEcPublicKey() {
    var garbage = Base64.getEncoder().encodeToString("definitely-not-an-spki-key".getBytes());
    var properties = properties(KEY_A, List.of(garbage));

    assertThatThrownBy(() -> config.tokenSigningKeys(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SPKI");
  }

  @Test
  @DisplayName("Should fail fast when retired key curve not P-256")
  void shouldFailFastWhenRetiredKeyCurveNotP256() throws Exception {
    var generator = java.security.KeyPairGenerator.getInstance("EC");
    generator.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
    var p384Public =
        Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    var properties = properties(KEY_A, List.of(p384Public));

    assertThatThrownBy(() -> config.tokenSigningKeys(properties))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("P-256");
  }

  @Test
  @DisplayName("Should ignore blank retired key entries when rotating")
  void shouldIgnoreBlankRetiredKeyEntriesWhenRotating() {
    var keys = config.tokenSigningKeys(properties(KEY_A, List.of("")));

    // A blank env entry is configuration noise, not a key: only the current key is served.
    assertThat(keys.verificationKeys().getKeys()).hasSize(1);
  }

  private String mint(JwtEncoder encoder) {
    return mint(encoder, TokenContract.ISSUER);
  }

  private String mint(JwtEncoder encoder, String issuer) {
    var now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(accountId.toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .claim(TokenClaims.ROLE, AccountRole.USER.name())
            .claim(TokenClaims.SESSION_ID, sessionId.toString())
            .claim(TokenClaims.SESSION_VERSION, 0L)
            .claim(TokenClaims.SCOPE, TokenScope.ACCOUNT.claimValue())
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.ES256).build(), claims))
        .getTokenValue();
  }

  private JwtDecoder decoder(TokenSigningKeys keys) {
    reader.sessionVersions.put(sessionId, 0L);
    return config.jwtDecoder(
        keys,
        new TokenIdentityValidator(),
        new TokenVersionValidator(new TokenVersionCache(reader)));
  }

  private static String tamperSignature(String token) {
    var parts = token.split("\\.", -1);
    var signature = Base64.getUrlDecoder().decode(parts[2]);
    signature[0] = (byte) (signature[0] ^ 1);
    parts[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    return String.join(".", parts);
  }

  private static AuthTokenProperties properties(String signingKey, List<String> verificationKeys) {
    return AuthTokenProperties.builder()
        .signingKey(signingKey)
        .verificationKeys(verificationKeys)
        .accessTokenTtl(Duration.ofMinutes(10))
        .refreshTokenTtl(Duration.ofDays(30))
        .rotationGrace(Duration.ofSeconds(30))
        .build();
  }
}
