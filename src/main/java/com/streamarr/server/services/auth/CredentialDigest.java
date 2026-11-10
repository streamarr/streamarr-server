package com.streamarr.server.services.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** The SHA-256 digest a credential is stored and recognised by, in place of the credential. */
public final class CredentialDigest {

  private CredentialDigest() {}

  public static byte[] of(String credential) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(credential.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", e);
    }
  }
}
