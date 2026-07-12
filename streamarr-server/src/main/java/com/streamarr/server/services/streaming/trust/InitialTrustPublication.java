package com.streamarr.server.services.streaming.trust;

import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class InitialTrustPublication {

  private final UUID installationId;
  private final byte[] bootstrapRootSha256;
  private final byte[] rootCertificateDer;
  private final byte[] issuerCertificateDer;
  private final byte[] revocationSignerCertificateDer;

  private InitialTrustPublication(UUID installationId, EncodedCertificates certificates) {
    this.installationId = Objects.requireNonNull(installationId);
    this.bootstrapRootSha256 = sha256(certificates.root());
    this.rootCertificateDer = certificates.root();
    this.issuerCertificateDer = certificates.issuer();
    this.revocationSignerCertificateDer = certificates.revocationSigner();
  }

  public static InitialTrustPublication from(CertificateAuthorityMaterial material) {
    Objects.requireNonNull(material);
    try {
      return new InitialTrustPublication(
          material.installationId(), EncodedCertificates.from(material));
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException("Failed to encode installation trust certificates", e);
    }
  }

  public UUID installationId() {
    return installationId;
  }

  public byte[] bootstrapRootSha256() {
    return bootstrapRootSha256.clone();
  }

  public byte[] rootCertificateDer() {
    return rootCertificateDer.clone();
  }

  public byte[] issuerCertificateDer() {
    return issuerCertificateDer.clone();
  }

  public byte[] revocationSignerCertificateDer() {
    return revocationSignerCertificateDer.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof InitialTrustPublication that)) {
      return false;
    }
    return installationId.equals(that.installationId)
        && Arrays.equals(bootstrapRootSha256, that.bootstrapRootSha256)
        && Arrays.equals(rootCertificateDer, that.rootCertificateDer)
        && Arrays.equals(issuerCertificateDer, that.issuerCertificateDer)
        && Arrays.equals(revocationSignerCertificateDer, that.revocationSignerCertificateDer);
  }

  @Override
  public int hashCode() {
    var result = installationId.hashCode();
    result = 31 * result + Arrays.hashCode(bootstrapRootSha256);
    result = 31 * result + Arrays.hashCode(rootCertificateDer);
    result = 31 * result + Arrays.hashCode(issuerCertificateDer);
    return 31 * result + Arrays.hashCode(revocationSignerCertificateDer);
  }

  @Override
  public String toString() {
    return "InitialTrustPublication[installationId=" + installationId + "]";
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static final class EncodedCertificates {

    private final byte[] root;
    private final byte[] issuer;
    private final byte[] revocationSigner;

    private EncodedCertificates(byte[] root, byte[] issuer, byte[] revocationSigner) {
      this.root = root.clone();
      this.issuer = issuer.clone();
      this.revocationSigner = revocationSigner.clone();
    }

    static EncodedCertificates from(CertificateAuthorityMaterial material)
        throws CertificateEncodingException {
      return new EncodedCertificates(
          material.rootCertificate().getEncoded(),
          material.issuerCertificate().getEncoded(),
          material.revocationSignerCertificate().getEncoded());
    }

    byte[] root() {
      return root.clone();
    }

    byte[] issuer() {
      return issuer.clone();
    }

    byte[] revocationSigner() {
      return revocationSigner.clone();
    }
  }
}
