package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Certificate Issuance Completion Tests")
class CertificateIssuanceCompletionTest {

  @Test
  @DisplayName("Should reject a non-positive certificate completion signing epoch")
  void shouldRejectNonPositiveCertificateCompletionSigningEpoch() throws Exception {
    var certificate = encodedCertificate();

    for (var epoch : java.util.List.of(0L, -1L)) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> completion(epoch, certificate))
          .withMessage("Certificate completion signing epoch must be positive");
    }
  }

  private CertificateIssuanceCompletion completion(
      long signingFencingEpoch, EncodedWorkerCertificate certificate) {
    return CertificateIssuanceCompletion.builder()
        .requestId(UUID.randomUUID())
        .signingFencingEpoch(signingFencingEpoch)
        .certificate(certificate)
        .build();
  }

  private EncodedWorkerCertificate encodedCertificate() throws Exception {
    var certificate =
        new BuiltInCertificateAuthority()
            .create(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"))
            .issuerCertificate();
    return EncodedWorkerCertificate.fromDer(certificate.getEncoded());
  }
}
