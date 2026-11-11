package com.streamarr.server.services.auth;

import com.streamarr.server.exceptions.InvalidUserCodeException;
import java.util.Arrays;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Share codes in the form of the code a person types to link a Device ({@link UserCode}): eight
 * letters, shown in two groups of four. A code is stored and recognised by the digest of its
 * normalised form, so the code itself exists only in the answer to issuing it.
 *
 * @see <a
 *     href="https://github.com/streamarr/streamarr-adr/blob/main/adr/0024-identity-authority-by-relationship.adoc#share-codes">ADR
 *     0024 §Share codes</a>
 */
@Component
@RequiredArgsConstructor
public class ShareCodes {

  private final UserCodeGenerator userCodes;

  public IssuedShareCode issue() {
    var code = userCodes.generate();
    return new IssuedShareCode(UserCode.forDisplay(code), CredentialDigest.of(code));
  }

  /** The digest a typed code is recognised by; empty when the text is not in its form. */
  public Optional<byte[]> digestOf(String typed) {
    try {
      return Optional.of(CredentialDigest.of(UserCode.normalize(typed)));
    } catch (InvalidUserCodeException _) {
      return Optional.empty();
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
