package com.streamarr.server.services.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.stereotype.Component;

/**
 * Generates and verifies digest-at-rest {@code publicId.secret} one-time codes.
 *
 * @see <a
 *     href="https://github.com/streamarr/streamarr-adr/blob/main/adr/0024-identity-authority-by-relationship.adoc#_invitations">ADR
 *     0024 §Invitations</a>
 */
@Component
public class OpaqueOneTimeCodes {

  private static final int SECRET_BYTES = 32;
  private static final int PUBLIC_ID_BYTES = 9;

  private final StringKeyGenerator publicIds = keyGenerator(PUBLIC_ID_BYTES);
  private final StringKeyGenerator secrets = keyGenerator(SECRET_BYTES);

  public IssuedCode issue() {
    var publicId = publicIds.generateKey();
    var secret = secrets.generateKey();
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

  private static StringKeyGenerator keyGenerator(int bytes) {
    return new Base64StringKeyGenerator(Base64.getUrlEncoder().withoutPadding(), bytes);
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
