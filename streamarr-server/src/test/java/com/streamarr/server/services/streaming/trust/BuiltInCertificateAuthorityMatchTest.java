package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Built-in Certificate Authority Trust Matching Tests")
class BuiltInCertificateAuthorityMatchTest {

  private static final UUID INSTALLATION_ID =
      UUID.fromString("f097da43-35bf-4a7f-9916-3a9241ef3e38");
  private static final Instant ISSUED_AT = Instant.parse("2026-07-12T12:00:00Z");

  private BuiltInCertificateAuthority authority;
  private CertificateAuthorityMaterial material;

  @BeforeEach
  void createAuthorityMaterial() {
    authority = new BuiltInCertificateAuthority();
    material = authority.create(INSTALLATION_ID, ISSUED_AT);
  }

  @Test
  @DisplayName("Should accept the exact initial public trust projection")
  void shouldAcceptExactInitialPublicTrustProjection() {
    var trust = trust(trustBundleBuilder());

    assertThatCode(() -> authority.requireMatches(trust, material)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("Should reject a non-initial bundle before rotation is supported")
  void shouldRejectNonInitialBundleBeforeRotationIsSupported() {
    var trust = trust(trustBundleBuilder().version(2L));

    assertThatThrownBy(() -> authority.requireMatches(trust, material))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("version");
  }

  @Test
  @DisplayName("Should reject a substituted certificate in the public trust projection")
  void shouldRejectSubstitutedCertificateInPublicTrustProjection() {
    var foreign = authority.create(INSTALLATION_ID, ISSUED_AT);
    var trust = trust(trustBundleBuilder().issuers(List.of(foreign.issuerCertificate())));

    assertThatThrownBy(() -> authority.requireMatches(trust, material))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("public trust bundle");
  }

  @Test
  @DisplayName("Should reject a missing certificate role in the public trust projection")
  void shouldRejectMissingCertificateRoleInPublicTrustProjection() {
    var trust = trust(trustBundleBuilder().issuers(List.of()));

    assertThatThrownBy(() -> authority.requireMatches(trust, material))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("public trust bundle");
  }

  private InstallationTrust trust(PublicTrustBundle.PublicTrustBundleBuilder bundleBuilder) {
    var bundle = bundleBuilder.build();
    var root = bundle.trustAnchors().getFirst();
    return new InstallationTrust(INSTALLATION_ID, sha256(encoded(root)), bundle);
  }

  private PublicTrustBundle.PublicTrustBundleBuilder trustBundleBuilder() {
    return PublicTrustBundle.builder()
        .installationId(INSTALLATION_ID)
        .version(1L)
        .createdAt(ISSUED_AT)
        .trustAnchors(List.of(material.rootCertificate()))
        .issuers(List.of(material.issuerCertificate()))
        .revocationSigners(List.of(material.revocationSignerCertificate()));
  }

  private static byte[] encoded(X509Certificate certificate) {
    try {
      return certificate.getEncoded();
    } catch (CertificateEncodingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
