package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidUserCodeException;
import com.streamarr.server.properties.CredentialCodeProperties;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Share codes in the form of the code a person types to link a Device ({@link UserCode}): eight
 * letters, shown in two groups of four. A code is stored and recognised by the HMAC-SHA-256 of its
 * normalised form under a server key kept outside the database, so the code itself exists only in
 * the answer to issuing it, and stored digests alone do not let a reader try every code.
 *
 * @see <a
 *     href="https://github.com/streamarr/streamarr-adr/blob/main/adr/0024-identity-authority-by-relationship.adoc#share-codes">ADR
 *     0024 §Share codes</a>
 */
@Slf4j
@Component
public class ShareCodes {

  private static final String KEY_SETTING = "AUTH_SHARE_CODE_KEY";
  private static final int MINIMUM_KEY_BYTES = 32;

  private final UserCodeGenerator userCodes;
  private final SecretKey key;

  public ShareCodes(UserCodeGenerator userCodes, CredentialCodeProperties properties) {
    this.userCodes = userCodes;
    this.key = keyFrom(properties.shareCodeKey());
  }

  public IssuedShareCode issue() {
    var code = userCodes.generate();
    return new IssuedShareCode(UserCode.forDisplay(code), CredentialDigest.keyed(key, code));
  }

  /** The digest a typed code is recognised by; empty when the text is not in its form. */
  public Optional<byte[]> digestOf(String typed) {
    try {
      return Optional.of(CredentialDigest.keyed(key, UserCode.normalize(typed)));
    } catch (InvalidUserCodeException _) {
      return Optional.empty();
    }
  }

  private static SecretKey keyFrom(String configured) {
    if (configured == null || configured.isBlank()) {
      log.warn(
          "{} is not set; generated a share code key for this run. Live share codes stop working"
              + " when the server restarts.",
          KEY_SETTING);
      var generated = new byte[MINIMUM_KEY_BYTES];
      new SecureRandom().nextBytes(generated);
      return new SecretKeySpec(generated, CredentialDigest.KEYED_ALGORITHM);
    }

    var decoded = decode(configured.strip());
    if (decoded.length < MINIMUM_KEY_BYTES) {
      throw new IllegalStateException(
          KEY_SETTING + " must decode to at least " + MINIMUM_KEY_BYTES + " bytes.");
    }

    return new SecretKeySpec(decoded, CredentialDigest.KEYED_ALGORITHM);
  }

  private static byte[] decode(String configured) {
    try {
      return Base64.getDecoder().decode(configured);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(KEY_SETTING + " must be valid base64.", e);
    }
  }

  /** The code in its display form, for the caller once, and the digest the row stores. */
  public record IssuedShareCode(String code, byte[] digest) {

    /** The digest is copied in and out so no caller can change what will be stored. */
    public IssuedShareCode {
      digest = digest.clone();
    }

    @Override
    public byte[] digest() {
      return digest.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof IssuedShareCode(var otherCode, var otherDigest)
          && code.equals(otherCode)
          && Arrays.equals(digest, otherDigest);
    }

    @Override
    public int hashCode() {
      return 31 * code.hashCode() + Arrays.hashCode(digest);
    }

    @Override
    public String toString() {
      return "IssuedShareCode[code=REDACTED, digest=REDACTED]";
    }
  }
}
