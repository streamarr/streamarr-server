package com.streamarr.server.services.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Opaque one-time codes (ADR 0024 §Invitations): {@code publicId.secret}, where the secret is 256
 * random bits and only its SHA-256 digest is stored. The stable publicId resolves the row (and keys
 * the guessing budget); the digest comparison is constant-time. The full code is returned once at
 * issuance and never again.
 */
@Component
public class OpaqueCodes {

  private static final int SECRET_BYTES = 32;
  private static final int PUBLIC_ID_BYTES = 9;

  private final SecureRandom random = new SecureRandom();

  public IssuedCode issue() {
    var publicId = randomToken(PUBLIC_ID_BYTES);
    var secret = randomToken(SECRET_BYTES);
    return new IssuedCode(publicId, publicId + "." + secret, digestOf(secret));
  }

  /** Splits {@code publicId.secret}; empty when the shape is not even a code. */
  public Optional<PresentedCode> parse(String code) {
    if (code == null) {
      return Optional.empty();
    }
    var separator = code.indexOf('.');
    if (separator <= 0 || separator == code.length() - 1) {
      return Optional.empty();
    }
    return Optional.of(
        new PresentedCode(code.substring(0, separator), code.substring(separator + 1)));
  }

  public boolean matches(PresentedCode presented, byte[] storedDigest) {
    return MessageDigest.isEqual(digestOf(presented.secret()), storedDigest);
  }

  private String randomToken(int bytes) {
    var buffer = new byte[bytes];
    random.nextBytes(buffer);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
  }

  private static byte[] digestOf(String secret) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
    }
  }

  /** The issued triple; {@code code} goes to the caller once, {@code digest} to the row. */
  public record IssuedCode(String publicId, String code, byte[] digest) {

    @Override
    public boolean equals(Object other) {
      return other instanceof IssuedCode(var otherPublicId, var otherCode, var otherDigest)
          && publicId.equals(otherPublicId)
          && code.equals(otherCode)
          && Arrays.equals(digest, otherDigest);
    }

    @Override
    public int hashCode() {
      return Objects.hash(publicId, code, Arrays.hashCode(digest));
    }

    @Override
    public String toString() {
      return "IssuedCode[publicId=%s, code=REDACTED, digest=REDACTED]".formatted(publicId);
    }
  }

  /** A presented code split into its halves; never logged. */
  public record PresentedCode(String publicId, String secret) {

    @Override
    public String toString() {
      return "PresentedCode[publicId=%s, secret=REDACTED]".formatted(publicId);
    }
  }
}
