package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker SPIFFE Identity Mapper Tests")
class WorkerSpiffeIdentityMapperTest {

  private static final String TRUST_DOMAIN = "streamarr.test";
  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

  private final WorkerSpiffeIdentityMapper mapper = new WorkerSpiffeIdentityMapper(TRUST_DOMAIN);

  @Test
  @DisplayName("Should map the one allowed worker URI SAN")
  void shouldMapTheOneAllowedWorkerUriSan() throws Exception {
    var certificate =
        certificate(
            List.of(
                List.of(2, "worker.test"),
                List.of(6, "spiffe://streamarr.test/streamarr/worker/" + WORKER_ID)));

    assertThat(mapper.workerId(certificate)).isEqualTo(WORKER_ID);
  }

  @Test
  @DisplayName("Should reject a certificate without a URI SAN")
  void shouldRejectCertificateWithoutUriSan() throws Exception {
    var certificate = certificate(List.of(List.of(2, "worker.test")));

    assertRejected(certificate);
  }

  @Test
  @DisplayName("Should reject a certificate without subject alternative names")
  void shouldRejectCertificateWithoutSubjectAlternativeNames() throws Exception {
    assertRejected(certificate(null));
  }

  @Test
  @DisplayName("Should reject a malformed URI SAN value")
  void shouldRejectMalformedUriSanValue() throws Exception {
    assertRejected(certificate(List.of(List.of(6, 42))));
  }

  @Test
  @DisplayName("Should reject duplicate URI SAN identities")
  void shouldRejectDuplicateUriSanIdentities() throws Exception {
    var certificate =
        certificate(
            List.of(
                List.of(6, "spiffe://streamarr.test/streamarr/worker/" + WORKER_ID),
                List.of(
                    6,
                    "spiffe://streamarr.test/streamarr/worker/"
                        + UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"))));

    assertRejected(certificate);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "spiffe://another.test/streamarr/worker/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "spiffe://streamarr.test/streamarr/server/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "spiffe://streamarr.test/streamarr/worker/not-a-uuid",
        "spiffe://streamarr.test/streamarr/worker/1-1-1-1-1",
        "https://streamarr.test/streamarr/worker/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
      })
  @DisplayName("Should reject a URI SAN outside the configured worker identity path")
  void shouldRejectUriSanOutsideConfiguredWorkerIdentityPath(String uri) throws Exception {
    assertRejected(certificate(List.of(List.of(6, uri))));
  }

  @Test
  @DisplayName("Should reject an unreadable subject alternative name extension")
  void shouldRejectUnreadableSubjectAlternativeNameExtension() throws Exception {
    var certificate = mock(X509Certificate.class);
    when(certificate.getBasicConstraints()).thenReturn(-1);
    when(certificate.getSubjectAlternativeNames())
        .thenThrow(new CertificateParsingException("malformed"));

    assertRejected(certificate);
  }

  @Test
  @DisplayName("Should reject a CA certificate presented as a worker identity")
  void shouldRejectCaCertificatePresentedAsWorkerIdentity() throws Exception {
    var certificate = certificate(List.of(List.of(6, workerIdentity())));
    when(certificate.getBasicConstraints()).thenReturn(0);

    assertRejected(certificate);
  }

  @Test
  @DisplayName("Should reject a certificate whose key usage permits certificate signing")
  void shouldRejectCertificateWhoseKeyUsagePermitsCertificateSigning() throws Exception {
    var certificate = certificate(List.of(List.of(6, workerIdentity())));
    when(certificate.getKeyUsage()).thenReturn(keyUsage(5));

    assertRejected(certificate);
  }

  @Test
  @DisplayName("Should reject a certificate whose key usage permits CRL signing")
  void shouldRejectCertificateWhoseKeyUsagePermitsCrlSigning() throws Exception {
    var certificate = certificate(List.of(List.of(6, workerIdentity())));
    when(certificate.getKeyUsage()).thenReturn(keyUsage(6));

    assertRejected(certificate);
  }

  @Test
  @DisplayName("Should reject a malformed subject alternative name entry")
  void shouldRejectMalformedSubjectAlternativeNameEntry() throws Exception {
    var certificate =
        certificate(
            List.of(
                List.of(), List.of(6, "spiffe://streamarr.test/streamarr/worker/" + WORKER_ID)));

    assertRejected(certificate);
  }

  @Test
  @DisplayName("Should reject a URI subject alternative name without a value")
  void shouldRejectUriSubjectAlternativeNameWithoutValue() throws Exception {
    assertRejected(certificate(List.of(List.of(6))));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " ",
        "bad domain",
        "streamarr.test/path",
        "streamarr.test:443",
        "Streamarr.Test",
        "user@streamarr.test"
      })
  @DisplayName("Should reject an invalid worker trust domain")
  void shouldRejectInvalidWorkerTrustDomain(String trustDomain) {
    assertThatThrownBy(() -> new WorkerSpiffeIdentityMapper(trustDomain))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Worker trust domain");
  }

  private static String workerIdentity() {
    return "spiffe://streamarr.test/streamarr/worker/" + WORKER_ID;
  }

  private static boolean[] keyUsage(int assertedBit) {
    var usage = new boolean[9];
    usage[0] = true;
    usage[assertedBit] = true;
    return usage;
  }

  private X509Certificate certificate(List<List<?>> subjectAlternativeNames) throws Exception {
    var certificate = mock(X509Certificate.class);
    when(certificate.getBasicConstraints()).thenReturn(-1);
    when(certificate.getSubjectAlternativeNames()).thenReturn(subjectAlternativeNames);
    return certificate;
  }

  private void assertRejected(X509Certificate certificate) {
    assertThatThrownBy(() -> mapper.workerId(certificate))
        .isInstanceOf(WorkerIdentityException.class)
        .hasMessage("Worker certificate does not contain one allowed SPIFFE identity");
  }
}
