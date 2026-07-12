package com.streamarr.server.services.streaming.trust;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.UUID;

public final class CertificateAuthorityMaterial {

  private final UUID installationId;
  private final AuthorityChainMaterial authorityChain;

  CertificateAuthorityMaterial(UUID installationId, AuthorityChainMaterial authorityChain) {
    this.installationId = installationId;
    this.authorityChain = authorityChain;
  }

  public UUID installationId() {
    return installationId;
  }

  public PrivateKey rootPrivateKey() {
    return authorityChain.root().privateKey();
  }

  public X509Certificate rootCertificate() {
    return authorityChain.root().certificate();
  }

  public PrivateKey issuerPrivateKey() {
    return authorityChain.issuer().privateKey();
  }

  public X509Certificate issuerCertificate() {
    return authorityChain.issuer().certificate();
  }

  public PrivateKey revocationSignerPrivateKey() {
    return authorityChain.revocationSigner().privateKey();
  }

  public X509Certificate revocationSignerCertificate() {
    return authorityChain.revocationSigner().certificate();
  }

  @Override
  public String toString() {
    return "CertificateAuthorityMaterial[installationId="
        + installationId
        + ", privateKeys=REDACTED]";
  }

  record AuthorityChainMaterial(
      AuthorityKeyMaterial root,
      AuthorityKeyMaterial issuer,
      AuthorityKeyMaterial revocationSigner) {

    @Override
    public String toString() {
      return "AuthorityChainMaterial[privateKeys=REDACTED]";
    }
  }

  record AuthorityKeyMaterial(PrivateKey privateKey, X509Certificate certificate) {

    @Override
    public String toString() {
      return "AuthorityKeyMaterial[privateKey=REDACTED]";
    }
  }
}
