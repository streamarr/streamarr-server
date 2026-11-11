package com.streamarr.server.services.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/**
 * The digest a credential is stored and recognised by, in place of the credential: SHA-256 for a
 * long random secret, and HMAC-SHA-256 under a server key for a code short enough that a reader of
 * the stored digests could hash every possible code.
 */
public final class CredentialDigest {

  static final String KEYED_ALGORITHM = "HmacSHA256";

  private CredentialDigest() {}

  public static byte[] of(String credential) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(credential.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
    }
  }

  static byte[] keyed(SecretKey key, String credential) {
    try {
      var mac = Mac.getInstance(KEYED_ALGORITHM);
      mac.init(key);
      return mac.doFinal(credential.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is a mandatory JCA algorithm", e);
    }
  }
}
