package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Certificate Issuance Value Tests")
class CertificateIssuanceValueTest {

  @Test
  @DisplayName("Should protect bounded subject public key info DER by value")
  void shouldProtectBoundedSubjectPublicKeyInfoDerByValue() {
    var source = new byte[] {1, 2, 3};
    var subjectPublicKeyInfo = SubjectPublicKeyInfo.fromDer(source);
    var equal = SubjectPublicKeyInfo.fromDer(source);
    source[0] = 9;
    var returned = subjectPublicKeyInfo.der();
    returned[1] = 9;

    assertThat(subjectPublicKeyInfo.der()).containsExactly(1, 2, 3);
    assertThat(subjectPublicKeyInfo).isEqualTo(equal).hasSameHashCodeAs(equal);
    assertThat(subjectPublicKeyInfo).isEqualTo(subjectPublicKeyInfo);
    assertThat(subjectPublicKeyInfo)
        .isNotEqualTo(SubjectPublicKeyInfo.fromDer(new byte[] {1, 2, 4}))
        .isNotEqualTo(new Object());
    assertThat(subjectPublicKeyInfo.toString())
        .isEqualTo("SubjectPublicKeyInfo[length=3]")
        .doesNotContain(Arrays.toString(subjectPublicKeyInfo.der()));
    assertThat(SubjectPublicKeyInfo.fromDer(new byte[4096]).der()).hasSize(4096);
    assertThatThrownBy(() -> SubjectPublicKeyInfo.fromDer(new byte[0]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 4096");
    assertThatThrownBy(() -> SubjectPublicKeyInfo.fromDer(new byte[4097]))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("between 1 and 4096");
  }

  @Test
  @DisplayName("Should require ordered whole-second certificate validity")
  void shouldRequireOrderedWholeSecondCertificateValidity() {
    var start = Instant.parse("2026-07-13T12:00:00Z");
    var end = start.plusSeconds(1);
    var fractionalStart = start.plusNanos(1);
    var fractionalEnd = end.plusNanos(1);

    assertThat(new CertificateValidity(start, end)).isEqualTo(new CertificateValidity(start, end));
    for (var invalidEnd : java.util.List.of(start, start.minusSeconds(1))) {
      assertThatThrownBy(() -> new CertificateValidity(start, invalidEnd))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("follow");
    }
    assertThatThrownBy(() -> new CertificateValidity(fractionalStart, end))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("whole-second");
    assertThatThrownBy(() -> new CertificateValidity(start, fractionalEnd))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("whole-second");
  }

  @Test
  @DisplayName("Should preserve bounded certificate serial numbers as unsigned bytes")
  void shouldPreserveBoundedCertificateSerialNumbersAsUnsignedBytes() {
    var highBitSerialBytes = new byte[] {(byte) 0x80, 1};
    var highBitSerial = CertificateSerialNumber.fromUnsignedBytes(highBitSerialBytes);
    var largestDerSerialBytes = new byte[20];
    Arrays.fill(largestDerSerialBytes, (byte) 0xff);
    largestDerSerialBytes[0] = 0x7f;

    assertThat(highBitSerial.value()).isEqualTo(new BigInteger(1, highBitSerialBytes));
    assertThat(highBitSerial.unsignedBytes()).containsExactly(highBitSerialBytes);
    assertThat(CertificateSerialNumber.fromUnsignedBytes(largestDerSerialBytes).unsignedBytes())
        .containsExactly(largestDerSerialBytes);
    for (var invalid : java.util.List.of(BigInteger.ZERO, BigInteger.ONE.negate())) {
      assertThatThrownBy(() -> new CertificateSerialNumber(invalid))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("positive");
    }
    var tooLargeUnsignedSerial = new byte[20];
    tooLargeUnsignedSerial[0] = (byte) 0x80;
    assertThatThrownBy(() -> CertificateSerialNumber.fromUnsignedBytes(tooLargeUnsignedSerial))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("20-byte DER integer");
  }

  @Test
  @DisplayName("Should reject unsupported worker certificate profile versions")
  void shouldRejectUnsupportedWorkerCertificateProfileVersions() {
    assertThat(WorkerCertificateProfile.fromVersion((short) 1))
        .isEqualTo(WorkerCertificateProfile.V1);
    assertThat(WorkerCertificateProfile.V1.version()).isEqualTo((short) 1);
    assertThatThrownBy(() -> WorkerCertificateProfile.fromVersion((short) 2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported");
  }

  @Test
  @DisplayName("Should reject a non-positive certificate signing epoch")
  void shouldRejectNonPositiveCertificateSigningEpoch() {
    for (var epoch : java.util.List.of(0L, -1L)) {
      assertThatThrownBy(() -> readyToSign(epoch))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("epoch");
    }
  }

  private CertificateIssuanceClaimResult.ReadyToSign readyToSign(long signingFencingEpoch) {
    var start = Instant.parse("2026-07-13T12:00:00Z");
    return CertificateIssuanceClaimResult.ReadyToSign.builder()
        .requestId(UUID.randomUUID())
        .workerId(UUID.randomUUID())
        .trustBundle(new PublicTrustBundleRef(UUID.randomUUID(), 1L))
        .subjectPublicKeyInfo(SubjectPublicKeyInfo.fromDer(new byte[] {1}))
        .parameters(
            CertificateIssuanceParameters.builder()
                .issuerCertificateSha256(new Sha256Digest(new byte[32]))
                .serialNumber(new CertificateSerialNumber(BigInteger.ONE))
                .profile(WorkerCertificateProfile.V1)
                .validity(new CertificateValidity(start, start.plusSeconds(1)))
                .build())
        .signingFencingEpoch(signingFencingEpoch)
        .build();
  }
}
