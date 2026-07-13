package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Worker Certificate Validator Tests")
class WorkerCertificateValidatorTest {

  private final WorkerCertificateValidator validator = new WorkerCertificateValidator();

  @Test
  @DisplayName("Should accept exact worker certificate profile")
  void shouldAcceptExactWorkerCertificateProfile() throws Exception {
    var fixture = fixture();

    var matches = validator.matches(encoded(fixture.shape()), fixture.validationContext());

    assertThat(matches).isTrue();
  }

  @Test
  @DisplayName("Should reject worker certificate using unsupported signature algorithm")
  void shouldRejectWorkerCertificateUsingUnsupportedSignatureAlgorithm() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(fixture.shape().toBuilder().signatureAlgorithm("SHA384withECDSA").build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with another issuer principal")
  void shouldRejectWorkerCertificateWithAnotherIssuerPrincipal() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(fixture.shape().toBuilder().issuerName(new X500Name("CN=Another Issuer")).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate signed by another issuer key")
  void shouldRejectWorkerCertificateSignedByAnotherIssuerKey() throws Exception {
    var fixture = fixture();
    var currentWorker = fixture.shape().context();
    var rogueAuthority =
        new BuiltInCertificateAuthority()
            .create(
                currentWorker.installationId(),
                fixture.validationContext().parameters().validity().notBefore());
    var rogueSigningContext =
        WorkerCertificateTestFixture.WorkerContext.builder()
            .installationId(currentWorker.installationId())
            .workerId(currentWorker.workerId())
            .authorityMaterial(rogueAuthority)
            .subjectPublicKey(currentWorker.subjectPublicKey())
            .parameters(currentWorker.parameters())
            .build();
    var certificate = encoded(fixture.shape().toBuilder().context(rogueSigningContext).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate when pinned issuer digest differs")
  void shouldRejectWorkerCertificateWhenPinnedIssuerDigestDiffers() throws Exception {
    var fixture = fixture();
    var current = fixture.validationContext();
    var currentParameters = current.parameters();
    var mismatchedParameters =
        CertificateIssuanceParameters.builder()
            .issuerCertificateSha256(
                new Sha256Digest(MessageDigest.getInstance("SHA-256").digest(new byte[] {1})))
            .serialNumber(currentParameters.serialNumber())
            .profile(currentParameters.profile())
            .validity(currentParameters.validity())
            .build();
    var mismatchedContext =
        WorkerCertificateValidator.ValidationContext.builder()
            .installationId(current.installationId())
            .workerId(current.workerId())
            .subjectPublicKeyInfo(current.subjectPublicKeyInfo())
            .parameters(mismatchedParameters)
            .issuer(current.issuer())
            .build();

    var matches = validator.matches(encoded(fixture.shape()), mismatchedContext);

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate extending beyond issuer validity")
  void shouldRejectWorkerCertificateExtendingBeyondIssuerValidity() throws Exception {
    var fixture = fixture();
    var validity = fixture.validationContext().parameters().validity();
    var beyondIssuer =
        new CertificateValidity(
            validity.notBefore(),
            fixture.validationContext().issuer().getNotAfter().toInstant().plusSeconds(1));
    var outsideIssuer = withValidity(fixture, beyondIssuer);

    var matches =
        validator.matches(encoded(outsideIssuer.shape()), outsideIssuer.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate beginning before issuer validity")
  void shouldRejectWorkerCertificateBeginningBeforeIssuerValidity() throws Exception {
    var fixture = fixture();
    var validity = fixture.validationContext().parameters().validity();
    var beforeIssuer =
        new CertificateValidity(
            fixture.validationContext().issuer().getNotBefore().toInstant().minusSeconds(1),
            validity.notAfter());
    var outsideIssuer = withValidity(fixture, beforeIssuer);

    var matches =
        validator.matches(encoded(outsideIssuer.shape()), outsideIssuer.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate when not after differs from claim")
  void shouldRejectWorkerCertificateWhenNotAfterDiffersFromClaim() throws Exception {
    var fixture = fixture();
    var expectedValidity = fixture.validationContext().parameters().validity();
    var differentValidity =
        new CertificateValidity(
            expectedValidity.notBefore(), expectedValidity.notAfter().minusSeconds(1));
    var differentCertificate = withValidity(fixture, differentValidity);

    var matches =
        validator.matches(encoded(differentCertificate.shape()), fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with another subject")
  void shouldRejectWorkerCertificateWithAnotherSubject() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(fixture.shape().toBuilder().subjectName(new X500Name("CN=Another Worker")).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @ParameterizedTest
  @EnumSource(SubjectAlternativeNameMismatch.class)
  @DisplayName("Should reject worker certificate when subject alternative name is not exact")
  void shouldRejectWorkerCertificateWhenSubjectAlternativeNameIsNotExact(
      SubjectAlternativeNameMismatch mismatch) throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(
            fixture.shape().toBuilder()
                .subjectAlternativeNames(invalidSubjectAlternativeNames(fixture, mismatch))
                .build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with malformed subject alternative name")
  void shouldRejectWorkerCertificateWithMalformedSubjectAlternativeName() throws Exception {
    var fixture = fixture();
    var malformedExtension =
        new WorkerCertificateTestFixture.AdditionalExtension(
            Extension.subjectAlternativeName, false, new DERUTF8String("malformed"));
    var certificate =
        encoded(
            fixture.shape().toBuilder()
                .subjectAlternativeNames(null)
                .additionalExtensions(List.of(malformedExtension))
                .build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with subject unique identifier")
  void shouldRejectWorkerCertificateWithSubjectUniqueIdentifier() throws Exception {
    var fixture = fixture();
    var certificate = encoded(fixture.shape().toBuilder().subjectUniqueId(true).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with issuer unique identifier")
  void shouldRejectWorkerCertificateWithIssuerUniqueIdentifier() throws Exception {
    var fixture = fixture();
    var certificate = encoded(fixture.shape().toBuilder().issuerUniqueId(true).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with unexpected extension")
  void shouldRejectWorkerCertificateWithUnexpectedExtension() throws Exception {
    var fixture = fixture();
    var extension =
        new WorkerCertificateTestFixture.AdditionalExtension(
            new ASN1ObjectIdentifier("1.3.6.1.4.1.55555.1"),
            false,
            new DERUTF8String("unexpected"));
    var certificate =
        encoded(fixture.shape().toBuilder().additionalExtensions(List.of(extension)).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate when required extension is not critical")
  void shouldRejectWorkerCertificateWhenRequiredExtensionIsNotCritical() throws Exception {
    var fixture = fixture();
    var certificate = encoded(fixture.shape().toBuilder().keyUsageCritical(false).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with CA basic constraints")
  void shouldRejectWorkerCertificateWithCaBasicConstraints() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(fixture.shape().toBuilder().basicConstraints(new BasicConstraints(true)).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with another key usage")
  void shouldRejectWorkerCertificateWithAnotherKeyUsage() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(
            fixture.shape().toBuilder()
                .keyUsage(new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment))
                .build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate without digital signature usage")
  void shouldRejectWorkerCertificateWithoutDigitalSignatureUsage() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(
            fixture.shape().toBuilder().keyUsage(new KeyUsage(KeyUsage.keyEncipherment)).build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate missing client authentication usage")
  void shouldRejectWorkerCertificateMissingClientAuthenticationUsage() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(
            fixture.shape().toBuilder()
                .extendedKeyUsage(new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                .build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with unexpected extended key usage")
  void shouldRejectWorkerCertificateWithUnexpectedExtendedKeyUsage() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(
            fixture.shape().toBuilder()
                .extendedKeyUsage(
                    new ExtendedKeyUsage(
                        new KeyPurposeId[] {
                          KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_codeSigning
                        }))
                .build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with wrong subject key identifier")
  void shouldRejectWorkerCertificateWithWrongSubjectKeyIdentifier() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(
            fixture.shape().toBuilder()
                .subjectKeyIdentifier(new SubjectKeyIdentifier(new byte[20]))
                .build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  @Test
  @DisplayName("Should reject worker certificate with wrong authority key identifier")
  void shouldRejectWorkerCertificateWithWrongAuthorityKeyIdentifier() throws Exception {
    var fixture = fixture();
    var certificate =
        encoded(
            fixture.shape().toBuilder()
                .authorityKeyIdentifier(new AuthorityKeyIdentifier(new byte[20]))
                .build());

    var matches = validator.matches(certificate, fixture.validationContext());

    assertThat(matches).isFalse();
  }

  private ValidatorFixture fixture() throws Exception {
    var installationId = UUID.randomUUID();
    var workerId = UUID.randomUUID();
    var issuedAt = Instant.parse("2026-01-01T00:00:00Z");
    var authority = new BuiltInCertificateAuthority().create(installationId, issuedAt);
    var keyGenerator = KeyPairGenerator.getInstance("EC");
    keyGenerator.initialize(new ECGenParameterSpec("secp256r1"));
    var subjectPublicKey = keyGenerator.generateKeyPair().getPublic();
    var parameters =
        CertificateIssuanceParameters.builder()
            .issuerCertificateSha256(
                new Sha256Digest(
                    MessageDigest.getInstance("SHA-256")
                        .digest(authority.issuerCertificate().getEncoded())))
            .serialNumber(new CertificateSerialNumber(new BigInteger("12345678901234567890")))
            .profile(WorkerCertificateProfile.V1)
            .validity(new CertificateValidity(issuedAt, issuedAt.plus(Duration.ofDays(7))))
            .build();
    var workerContext =
        WorkerCertificateTestFixture.WorkerContext.builder()
            .installationId(installationId)
            .workerId(workerId)
            .authorityMaterial(authority)
            .subjectPublicKey(subjectPublicKey)
            .parameters(parameters)
            .build();
    var validationContext =
        WorkerCertificateValidator.ValidationContext.builder()
            .installationId(installationId)
            .workerId(workerId)
            .subjectPublicKeyInfo(SubjectPublicKeyInfo.from(subjectPublicKey))
            .parameters(parameters)
            .issuer(authority.issuerCertificate())
            .build();
    return ValidatorFixture.builder()
        .validationContext(validationContext)
        .shape(WorkerCertificateTestFixture.validShape(workerContext).build())
        .build();
  }

  private EncodedWorkerCertificate encoded(WorkerCertificateTestFixture.CertificateShape shape)
      throws Exception {
    return EncodedWorkerCertificate.fromDer(
        WorkerCertificateTestFixture.certificate(shape).getEncoded());
  }

  private GeneralNames invalidSubjectAlternativeNames(
      ValidatorFixture fixture, SubjectAlternativeNameMismatch mismatch) {
    var expected = fixture.shape().subjectAlternativeNames().getNames()[0];
    return switch (mismatch) {
      case MISSING -> null;
      case MULTIPLE ->
          new GeneralNames(
              new GeneralName[] {expected, new GeneralName(GeneralName.dNSName, "worker.local")});
      case NON_URI -> new GeneralNames(new GeneralName(GeneralName.dNSName, "worker.local"));
      case WRONG_URI ->
          new GeneralNames(
              new GeneralName(
                  GeneralName.uniformResourceIdentifier,
                  "spiffe://00000000-0000-0000-0000-000000000000/streamarr/worker/unknown"));
    };
  }

  private ValidatorFixture withValidity(
      ValidatorFixture fixture, CertificateValidity certificateValidity) {
    var current = fixture.validationContext();
    var currentParameters = current.parameters();
    var parameters =
        CertificateIssuanceParameters.builder()
            .issuerCertificateSha256(currentParameters.issuerCertificateSha256())
            .serialNumber(currentParameters.serialNumber())
            .profile(currentParameters.profile())
            .validity(certificateValidity)
            .build();
    var currentWorker = fixture.shape().context();
    var workerContext =
        WorkerCertificateTestFixture.WorkerContext.builder()
            .installationId(currentWorker.installationId())
            .workerId(currentWorker.workerId())
            .authorityMaterial(currentWorker.authorityMaterial())
            .subjectPublicKey(currentWorker.subjectPublicKey())
            .parameters(parameters)
            .build();
    var validationContext =
        WorkerCertificateValidator.ValidationContext.builder()
            .installationId(current.installationId())
            .workerId(current.workerId())
            .subjectPublicKeyInfo(current.subjectPublicKeyInfo())
            .parameters(parameters)
            .issuer(current.issuer())
            .build();
    return ValidatorFixture.builder()
        .validationContext(validationContext)
        .shape(fixture.shape().toBuilder().context(workerContext).build())
        .build();
  }

  @Builder
  private record ValidatorFixture(
      WorkerCertificateValidator.ValidationContext validationContext,
      WorkerCertificateTestFixture.CertificateShape shape) {}

  private enum SubjectAlternativeNameMismatch {
    MISSING,
    MULTIPLE,
    NON_URI,
    WRONG_URI
  }
}
