package com.streamarr.server.services.streaming.trust;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Objects;

public final class SubjectPublicKeyInfo {

  private static final int MAXIMUM_DER_LENGTH = 4096;

  private final byte[] der;

  private SubjectPublicKeyInfo(byte[] der) {
    Objects.requireNonNull(der);
    if (der.length == 0 || der.length > MAXIMUM_DER_LENGTH) {
      throw new IllegalArgumentException(
          "Subject public key info DER must contain between 1 and 4096 bytes");
    }
    this.der = der.clone();
  }

  public static SubjectPublicKeyInfo from(PublicKey publicKey) {
    Objects.requireNonNull(publicKey);
    return fromDer(publicKey.getEncoded());
  }

  public static SubjectPublicKeyInfo fromDer(byte[] der) {
    return new SubjectPublicKeyInfo(der);
  }

  public byte[] der() {
    return der.clone();
  }

  public Sha256Digest sha256() {
    try {
      return new Sha256Digest(MessageDigest.getInstance("SHA-256").digest(der));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof SubjectPublicKeyInfo that && Arrays.equals(der, that.der);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(der);
  }

  @Override
  public String toString() {
    return "SubjectPublicKeyInfo[length=" + der.length + "]";
  }
}
