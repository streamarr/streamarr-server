package com.streamarr.server.services.streaming.trust;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;

public final class EncodedWorkerCertificate {

  private static final int MAXIMUM_DER_LENGTH = 65_536;

  private final byte[] der;

  private EncodedWorkerCertificate(byte[] der) {
    this.der = requireCanonicalDer(der);
  }

  public static EncodedWorkerCertificate fromDer(byte[] der) {
    return new EncodedWorkerCertificate(der);
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

  public X509Certificate x509Certificate() {
    try {
      return (X509Certificate)
          CertificateFactory.getInstance("X.509")
              .generateCertificate(new ByteArrayInputStream(der));
    } catch (CertificateException e) {
      throw new IllegalStateException("Validated worker certificate cannot be parsed", e);
    }
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof EncodedWorkerCertificate that && Arrays.equals(der, that.der);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(der);
  }

  @Override
  public String toString() {
    return "EncodedWorkerCertificate[length=" + der.length + "]";
  }

  private static byte[] requireCanonicalDer(byte[] der) {
    Objects.requireNonNull(der);
    if (der.length == 0 || der.length > MAXIMUM_DER_LENGTH) {
      throw new IllegalArgumentException(
          "Worker certificate DER must contain between 1 and 65536 bytes");
    }
    try {
      var input = new ByteArrayInputStream(der);
      var certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      if (input.available() != 0 || !MessageDigest.isEqual(der, certificate.getEncoded())) {
        throw new IllegalArgumentException("Worker certificate must use canonical X.509 DER");
      }
      return der.clone();
    } catch (CertificateException e) {
      throw new IllegalArgumentException("Worker certificate must contain valid X.509 DER", e);
    }
  }
}
