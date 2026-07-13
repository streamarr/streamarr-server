package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Encoded Worker Certificate Tests")
class EncodedWorkerCertificateTest {

  @Test
  @DisplayName("Should protect canonical certificate DER from caller mutation")
  void shouldProtectCanonicalCertificateDerFromCallerMutation() throws Exception {
    var source = certificateDer();
    var expected = source.clone();
    var certificate = EncodedWorkerCertificate.fromDer(source);
    source[0] ^= 1;
    var exposed = certificate.der();
    exposed[0] ^= 1;

    assertThat(certificate.der()).isEqualTo(expected);
    assertThat(certificate.sha256().bytes())
        .isEqualTo(MessageDigest.getInstance("SHA-256").digest(expected));
    assertThat(certificate.x509Certificate().getEncoded()).isEqualTo(expected);
  }

  @Test
  @DisplayName("Should compare encoded certificates by content without exposing DER")
  void shouldCompareEncodedCertificatesByContentWithoutExposingDer() throws Exception {
    var der = certificateDer();
    var first = EncodedWorkerCertificate.fromDer(der);
    var second = EncodedWorkerCertificate.fromDer(der.clone());
    var different = EncodedWorkerCertificate.fromDer(certificateDer());

    assertThat(first)
        .isEqualTo(second)
        .hasSameHashCodeAs(second)
        .isNotEqualTo(different)
        .isNotEqualTo("certificate")
        .hasToString("EncodedWorkerCertificate[length=" + der.length + "]");
    assertThat(first.equals(first)).isTrue();
  }

  @Test
  @DisplayName("Should reject missing or out-of-bounds certificate DER")
  void shouldRejectMissingOrOutOfBoundsCertificateDer() {
    assertThatNullPointerException().isThrownBy(() -> EncodedWorkerCertificate.fromDer(null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EncodedWorkerCertificate.fromDer(new byte[0]))
        .withMessage("Worker certificate DER must contain between 1 and 65536 bytes");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EncodedWorkerCertificate.fromDer(new byte[65_537]))
        .withMessage("Worker certificate DER must contain between 1 and 65536 bytes");
  }

  @Test
  @DisplayName("Should reject invalid or noncanonical certificate DER")
  void shouldRejectInvalidOrNoncanonicalCertificateDer() throws Exception {
    var valid = certificateDer();
    var trailing = Arrays.copyOf(valid, valid.length + 1);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> EncodedWorkerCertificate.fromDer(new byte[] {1}))
        .withMessage("Worker certificate must contain valid X.509 DER");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> EncodedWorkerCertificate.fromDer(trailing))
        .withMessage("Worker certificate must use canonical X.509 DER");
  }

  private byte[] certificateDer() throws Exception {
    return new BuiltInCertificateAuthority()
        .create(UUID.randomUUID(), Instant.parse("2026-01-01T00:00:00Z"))
        .issuerCertificate()
        .getEncoded();
  }
}
