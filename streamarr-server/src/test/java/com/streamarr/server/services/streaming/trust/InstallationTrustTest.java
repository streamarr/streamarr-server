package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Installation Trust Tests")
class InstallationTrustTest {

  private static final UUID INSTALLATION_ID =
      UUID.fromString("00cf994e-8fab-498b-ac5b-02ca7d41c2a2");

  private PublicTrustBundle bundle;

  @BeforeEach
  void createBundle() {
    var material =
        new BuiltInCertificateAuthority()
            .create(INSTALLATION_ID, Instant.parse("2026-07-12T12:00:00Z"));
    bundle =
        PublicTrustBundle.builder()
            .installationId(INSTALLATION_ID)
            .version(1L)
            .createdAt(Instant.parse("2026-07-12T12:00:00Z"))
            .trustAnchors(List.of(material.rootCertificate()))
            .issuers(List.of(material.issuerCertificate()))
            .revocationSigners(List.of(material.revocationSignerCertificate()))
            .build();
  }

  @Test
  @DisplayName("Should reject a root fingerprint with the wrong length")
  void shouldRejectRootFingerprintWithWrongLength() {
    var rootFingerprint = new byte[31];

    assertThatThrownBy(() -> new InstallationTrust(INSTALLATION_ID, rootFingerprint, bundle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fingerprint");
  }

  @Test
  @DisplayName("Should reject an active bundle from another installation")
  void shouldRejectActiveBundleFromAnotherInstallation() {
    var otherInstallationId = UUID.randomUUID();
    var rootFingerprint = new byte[32];

    assertThatThrownBy(() -> new InstallationTrust(otherInstallationId, rootFingerprint, bundle))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("installation");
  }

  @Test
  @DisplayName("Should reject a non-positive public trust bundle version")
  void shouldRejectNonPositivePublicTrustBundleVersion() {
    var bundleBuilder =
        PublicTrustBundle.builder()
            .installationId(bundle.installationId())
            .version(0L)
            .createdAt(bundle.createdAt())
            .trustAnchors(bundle.trustAnchors())
            .issuers(bundle.issuers())
            .revocationSigners(bundle.revocationSigners());

    assertThatThrownBy(bundleBuilder::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  @Test
  @DisplayName("Should protect the root fingerprint from caller mutation")
  void shouldProtectRootFingerprintFromCallerMutation() {
    var rootFingerprint = new byte[32];
    var trust = new InstallationTrust(INSTALLATION_ID, rootFingerprint, bundle);

    rootFingerprint[0] = 1;
    var exposedFingerprint = trust.bootstrapRootSha256();
    exposedFingerprint[1] = 1;

    assertThat(trust.bootstrapRootSha256()).containsOnly(0);
  }
}
