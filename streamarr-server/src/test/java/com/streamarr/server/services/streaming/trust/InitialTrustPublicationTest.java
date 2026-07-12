package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Initial Trust Publication Tests")
class InitialTrustPublicationTest {

  private static final UUID INSTALLATION_ID =
      UUID.fromString("61df963e-4e44-4a34-a397-f6ae176900ea");

  private CertificateAuthorityMaterial material;

  @BeforeEach
  void createAuthorityMaterial() {
    material =
        new BuiltInCertificateAuthority()
            .create(INSTALLATION_ID, Instant.parse("2026-07-12T12:00:00Z"));
  }

  @Test
  @DisplayName("Should compare equal when publications contain the same encoded material")
  void shouldCompareEqualWhenPublicationsContainSameEncodedMaterial() {
    var first = InitialTrustPublication.from(material);
    var second = InitialTrustPublication.from(material);

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  @Test
  @DisplayName("Should protect encoded material from caller mutation")
  void shouldProtectEncodedMaterialFromCallerMutation() {
    var publication = InitialTrustPublication.from(material);
    var expectedRoot = publication.rootCertificateDer();
    var exposedRoot = publication.rootCertificateDer();
    exposedRoot[0] = (byte) ~exposedRoot[0];

    assertThat(publication.rootCertificateDer()).isEqualTo(expectedRoot);
  }

  @Test
  @DisplayName("Should omit certificate encodings from diagnostic text")
  void shouldOmitCertificateEncodingsFromDiagnosticText() {
    var publication = InitialTrustPublication.from(material);

    assertThat(publication.toString())
        .isEqualTo("InitialTrustPublication[installationId=" + INSTALLATION_ID + "]");
  }
}
