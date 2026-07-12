package com.streamarr.server.services.streaming.trust;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Built-in Certificate Authority Tests")
class BuiltInCertificateAuthorityTest {

  private static final UUID INSTALLATION_ID =
      UUID.fromString("7afbbbed-3492-4b71-909d-8acb50ffdcc2");
  private static final Instant ISSUED_AT = Instant.parse("2026-07-12T12:00:00Z");

  @Test
  @DisplayName("Should create the exact installation trust profiles when material is generated")
  void shouldCreateExactInstallationTrustProfilesWhenMaterialIsGenerated() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var material = authority.create(INSTALLATION_ID, ISSUED_AT);
    var root = material.rootCertificate();
    var issuer = material.issuerCertificate();
    var revocationSigner = material.revocationSignerCertificate();

    verifyCertificateSignatures(root, issuer, revocationSigner);
    verifyPrivateKeyProfiles(material);
    verifyPublicKeyProfiles(root, issuer, revocationSigner);
    verifyAuthorityRoleProfiles(root, issuer, revocationSigner);
    verifyExtensionProfiles(root, issuer, revocationSigner);
    verifyTrustDomainProfiles(root, issuer, revocationSigner);
    verifyValidityProfiles(root, issuer, revocationSigner);
    verifyCertificateDigests(root, issuer, revocationSigner);
    authority.validate(material, INSTALLATION_ID, ISSUED_AT);
  }

  @Test
  @DisplayName("Should reject authority material when a private key mismatches its certificate")
  void shouldRejectAuthorityMaterialWhenPrivateKeyMismatchesCertificate() {
    var authority = new BuiltInCertificateAuthority();
    var expected = authority.create(INSTALLATION_ID, ISSUED_AT);
    var other =
        authority.create(UUID.fromString("43b81699-381f-44f8-b675-aa326a44fa7c"), ISSUED_AT);
    var mismatched =
        new CertificateAuthorityMaterial(
            INSTALLATION_ID,
            new CertificateAuthorityMaterial.AuthorityChainMaterial(
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    other.rootPrivateKey(), expected.rootCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    expected.issuerPrivateKey(), expected.issuerCertificate()),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    expected.revocationSignerPrivateKey(),
                    expected.revocationSignerCertificate())));

    assertThatThrownBy(() -> authority.validate(mismatched, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("mismatched private key");
  }

  @Test
  @DisplayName("Should reject authority material when a private key is not EC")
  void shouldRejectAuthorityMaterialWhenPrivateKeyIsNotEc() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var expected = authority.create(INSTALLATION_ID, ISSUED_AT);
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var malformed = withRootPrivateKey(expected, generator.generateKeyPair().getPrivate());

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when a private key is outside P-256")
  void shouldRejectAuthorityMaterialWhenPrivateKeyIsOutsideP256() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var expected = authority.create(INSTALLATION_ID, ISSUED_AT);
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp384r1"));
    var malformed = withRootPrivateKey(expected, generator.generateKeyPair().getPrivate());

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when it belongs to another installation")
  void shouldRejectAuthorityMaterialWhenItBelongsToAnotherInstallation() {
    var authority = new BuiltInCertificateAuthority();
    var material = authority.create(INSTALLATION_ID, ISSUED_AT);
    var otherInstallation = UUID.randomUUID();

    assertThatThrownBy(() -> authority.validate(material, otherInstallation, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("different installation");
  }

  @Test
  @DisplayName("Should reject a certificate chain when it is relabeled for this installation")
  void shouldRejectCertificateChainWhenRelabeledForThisInstallation() {
    var authority = new BuiltInCertificateAuthority();
    var foreign =
        authority.create(UUID.fromString("9da8e0fd-cd00-407a-a3be-44f79ce8357d"), ISSUED_AT);
    var relabeled = withInstallationId(foreign, INSTALLATION_ID);

    assertThatThrownBy(() -> authority.validate(relabeled, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when its certificates have expired")
  void shouldRejectAuthorityMaterialWhenCertificatesHaveExpired() {
    var authority = new BuiltInCertificateAuthority();
    var material = authority.create(INSTALLATION_ID, ISSUED_AT);
    var expiredAt = ISSUED_AT.plus(Duration.ofDays(4_000));

    assertThatThrownBy(() -> authority.validate(material, INSTALLATION_ID, expiredAt))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid")
        .hasCauseInstanceOf(CertificateExpiredException.class);
  }

  @Test
  @DisplayName("Should reject authority material when a subject key identifier is incorrect")
  void shouldRejectAuthorityMaterialWhenSubjectKeyIdentifierIsIncorrect() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var malformed = withMismatchedRootKeyIdentifiers(original);

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when two roles reuse one key")
  void shouldRejectAuthorityMaterialWhenRolesReuseOneKey() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var malformed = withIssuerKeyReusedForRevocationSigning(original);

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when an unexpected extension is present")
  void shouldRejectAuthorityMaterialWhenUnexpectedExtensionIsPresent() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var malformed = withUnexpectedRootExtension(original);

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when a serial number exceeds 128 bits")
  void shouldRejectAuthorityMaterialWhenSerialNumberExceeds128Bits() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var malformed =
        withRevocationSignerProfile(
            original,
            defaultRevocationSignerProfile(original)
                .withShape(
                    new CertificateShape(
                        BigInteger.ONE.shiftLeft(128),
                        original.revocationSignerCertificate().getNotAfter())));

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when root and issuer serial numbers collide")
  void shouldRejectAuthorityMaterialWhenRootAndIssuerSerialNumbersCollide() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var malformed =
        withIssuerProfile(
            original,
            new IssuerProfile(
                original.rootCertificate().getSerialNumber(),
                new X500Name(original.issuerCertificate().getSubjectX500Principal().getName())));

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when a trust-domain URI is not canonical")
  void shouldRejectAuthorityMaterialWhenTrustDomainUriIsNotCanonical() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var exact = exactRootExtensions(original.rootCertificate());
    var malformed =
        withRootExtensions(
            original,
            new RootExtensions(
                exact.subjectKeyIdentifier(),
                exact.authorityKeyIdentifier(),
                "SPIFFE://" + INSTALLATION_ID));

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when an authority key identifier is ambiguous")
  void shouldRejectAuthorityMaterialWhenAuthorityKeyIdentifierIsAmbiguous() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var exact = exactRootExtensions(original.rootCertificate());
    var ambiguousIdentifier =
        new AuthorityKeyIdentifier(
            exact.authorityKeyIdentifier().getKeyIdentifier(),
            new GeneralNames(
                new GeneralName(GeneralName.directoryName, new X500Name("CN=Conflicting Root"))),
            BigInteger.ONE);
    var malformed =
        withRootExtensions(
            original,
            new RootExtensions(
                exact.subjectKeyIdentifier(), ambiguousIdentifier, exact.trustDomain()));

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when an authority role has an ambiguous subject")
  void shouldRejectAuthorityMaterialWhenAuthorityRoleHasAmbiguousSubject() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var malformed =
        withIssuerProfile(
            original,
            new IssuerProfile(
                original.issuerCertificate().getSerialNumber(),
                new X500Name(original.rootCertificate().getSubjectX500Principal().getName())));

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  @Test
  @DisplayName("Should reject authority material when a role validity exceeds its profile")
  void shouldRejectAuthorityMaterialWhenRoleValidityExceedsProfile() throws Exception {
    var authority = new BuiltInCertificateAuthority();
    var original = authority.create(INSTALLATION_ID, ISSUED_AT);
    var malformed =
        withRevocationSignerProfile(
            original,
            defaultRevocationSignerProfile(original)
                .withShape(
                    new CertificateShape(
                        original.revocationSignerCertificate().getSerialNumber(),
                        original.issuerCertificate().getNotAfter())));

    assertThatThrownBy(() -> authority.validate(malformed, INSTALLATION_ID, ISSUED_AT))
        .isInstanceOf(InstallationTrustException.class)
        .hasMessageContaining("invalid");
  }

  private static void verifyCertificateSignatures(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner)
      throws Exception {
    root.verify(root.getPublicKey());
    issuer.verify(root.getPublicKey());
    revocationSigner.verify(issuer.getPublicKey());
  }

  private static void verifyPrivateKeyProfiles(CertificateAuthorityMaterial material) {
    assertThat(material.rootPrivateKey()).isInstanceOf(ECPrivateKey.class);
    assertThat(material.issuerPrivateKey()).isInstanceOf(ECPrivateKey.class);
    assertThat(material.revocationSignerPrivateKey()).isInstanceOf(ECPrivateKey.class);
  }

  private static void verifyPublicKeyProfiles(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner) {
    assertThat((ECPublicKey) root.getPublicKey())
        .extracting(key -> key.getParams().getCurve().getField().getFieldSize())
        .isEqualTo(256);
    assertThat((ECPublicKey) issuer.getPublicKey())
        .extracting(key -> key.getParams().getCurve().getField().getFieldSize())
        .isEqualTo(256);
    assertThat((ECPublicKey) revocationSigner.getPublicKey())
        .extracting(key -> key.getParams().getCurve().getField().getFieldSize())
        .isEqualTo(256);
  }

  private static void verifyAuthorityRoleProfiles(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner) {
    assertThat(root.getBasicConstraints()).isEqualTo(1);
    assertThat(issuer.getBasicConstraints()).isZero();
    assertThat(revocationSigner.getBasicConstraints()).isEqualTo(-1);
    assertThat(root.getSubjectX500Principal().getName())
        .isEqualTo("CN=Streamarr Installation 7afbbbed-3492-4b71-909d-8acb50ffdcc2 Root CA");
    assertThat(issuer.getSubjectX500Principal().getName())
        .isEqualTo("CN=Streamarr Installation 7afbbbed-3492-4b71-909d-8acb50ffdcc2 Issuing CA");
    assertThat(revocationSigner.getSubjectX500Principal().getName())
        .isEqualTo(
            "CN=Streamarr Installation 7afbbbed-3492-4b71-909d-8acb50ffdcc2 Revocation Signer");
    assertThat(root.getKeyUsage()).containsExactly(keyUsages(5, 6));
    assertThat(issuer.getKeyUsage()).containsExactly(keyUsages(5, 6));
    assertThat(revocationSigner.getKeyUsage()).containsExactly(keyUsages(0));
  }

  private static void verifyExtensionProfiles(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner) {
    assertThat(root.getCriticalExtensionOIDs())
        .contains(Extension.basicConstraints.getId(), Extension.keyUsage.getId());
    assertThat(issuer.getCriticalExtensionOIDs())
        .contains(Extension.basicConstraints.getId(), Extension.keyUsage.getId());
    assertThat(revocationSigner.getCriticalExtensionOIDs())
        .contains(
            Extension.basicConstraints.getId(),
            Extension.keyUsage.getId(),
            Extension.extendedKeyUsage.getId());
  }

  private static void verifyTrustDomainProfiles(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner)
      throws Exception {
    var trustDomain = URI.create("spiffe://" + INSTALLATION_ID);
    assertThat(uriSubjectAlternativeNames(root)).containsExactly(trustDomain);
    assertThat(uriSubjectAlternativeNames(issuer)).containsExactly(trustDomain);
    assertThat(revocationSigner.getSubjectAlternativeNames()).isNull();
    assertThat(root.getExtendedKeyUsage()).isNull();
    assertThat(issuer.getExtendedKeyUsage()).isNull();
    assertThat(revocationSigner.getExtendedKeyUsage())
        .containsExactly(BuiltInCertificateAuthority.REVOCATION_SIGNING_EKU_OID);
  }

  private static void verifyValidityProfiles(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner) {
    assertThat(root.getNotBefore().toInstant()).isBefore(ISSUED_AT);
    assertThat(issuer.getNotBefore()).isEqualTo(root.getNotBefore());
    assertThat(revocationSigner.getNotBefore()).isEqualTo(root.getNotBefore());
    assertThat(issuer.getNotAfter()).isBeforeOrEqualTo(root.getNotAfter());
    assertThat(revocationSigner.getNotAfter()).isBeforeOrEqualTo(issuer.getNotAfter());
  }

  private static void verifyCertificateDigests(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner)
      throws Exception {
    assertThat(sha256(root.getEncoded())).hasSize(32);
    assertThat(sha256(issuer.getEncoded())).hasSize(32);
    assertThat(sha256(revocationSigner.getEncoded())).hasSize(32);
  }

  private static CertificateAuthorityMaterial withUnexpectedRootExtension(
      CertificateAuthorityMaterial original) throws Exception {
    var root = original.rootCertificate();
    var builder = rootBuilder(original, root.getSerialNumber());
    addExactRootExtensions(builder, root);
    builder.addExtension(
        new ASN1ObjectIdentifier("1.3.6.1.4.1.55555.1"), false, new DERUTF8String("unexpected"));
    return withRootCertificate(original, signRoot(builder, original));
  }

  private static CertificateAuthorityMaterial withIssuerKeyReusedForRevocationSigning(
      CertificateAuthorityMaterial original) throws Exception {
    var issuer = original.issuerCertificate();
    var originalSigner = original.revocationSignerCertificate();
    var identity =
        new SigningIdentity(
            original.issuerPrivateKey(),
            issuer.getPublicKey(),
            new X500Name(originalSigner.getSubjectX500Principal().getName()));
    return withRevocationSignerProfile(
        original, defaultRevocationSignerProfile(original).withIdentity(identity));
  }

  private static CertificateAuthorityMaterial withMismatchedRootKeyIdentifiers(
      CertificateAuthorityMaterial original) throws Exception {
    var exact = exactRootExtensions(original.rootCertificate());
    return withRootExtensions(
        original,
        new RootExtensions(
            new SubjectKeyIdentifier(new byte[] {1, 2, 3, 4}),
            exact.authorityKeyIdentifier(),
            exact.trustDomain()));
  }

  private static CertificateAuthorityMaterial withRootExtensions(
      CertificateAuthorityMaterial original, RootExtensions profile) throws Exception {
    var builder = rootBuilder(original, original.rootCertificate().getSerialNumber());
    addRootExtensions(builder, profile);
    return withRootCertificate(original, signRoot(builder, original));
  }

  private static JcaX509v3CertificateBuilder rootBuilder(
      CertificateAuthorityMaterial original, BigInteger serialNumber) {
    var root = original.rootCertificate();
    var subject = new X500Name(root.getSubjectX500Principal().getName());
    return new JcaX509v3CertificateBuilder(
        subject,
        serialNumber,
        root.getNotBefore(),
        root.getNotAfter(),
        subject,
        root.getPublicKey());
  }

  private static void addExactRootExtensions(
      JcaX509v3CertificateBuilder builder, X509Certificate root) throws Exception {
    addRootExtensions(builder, exactRootExtensions(root));
  }

  private static RootExtensions exactRootExtensions(X509Certificate root) throws Exception {
    var extensions = new JcaX509ExtensionUtils();
    return new RootExtensions(
        extensions.createSubjectKeyIdentifier(root.getPublicKey()),
        extensions.createAuthorityKeyIdentifier(root.getPublicKey()),
        "spiffe://" + INSTALLATION_ID);
  }

  private static void addRootExtensions(JcaX509v3CertificateBuilder builder, RootExtensions profile)
      throws Exception {
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(1));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    builder.addExtension(Extension.subjectKeyIdentifier, false, profile.subjectKeyIdentifier());
    builder.addExtension(Extension.authorityKeyIdentifier, false, profile.authorityKeyIdentifier());
    addTrustDomain(builder, profile.trustDomain());
  }

  private static void addTrustDomain(JcaX509v3CertificateBuilder builder, String trustDomain)
      throws Exception {
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, trustDomain)));
  }

  private static CertificateAuthorityMaterial withIssuerProfile(
      CertificateAuthorityMaterial original, IssuerProfile profile) throws Exception {
    var root = original.rootCertificate();
    var issuer = original.issuerCertificate();
    var builder =
        new JcaX509v3CertificateBuilder(
            new X500Name(root.getSubjectX500Principal().getName()),
            profile.serialNumber(),
            issuer.getNotBefore(),
            issuer.getNotAfter(),
            profile.subject(),
            issuer.getPublicKey());
    addIssuerExtensions(builder, issuer.getPublicKey(), root);
    var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(original.rootPrivateKey());
    var rebuiltIssuer = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    var intermediate =
        new CertificateAuthorityMaterial(
            INSTALLATION_ID,
            new CertificateAuthorityMaterial.AuthorityChainMaterial(
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    original.rootPrivateKey(), root),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    original.issuerPrivateKey(), rebuiltIssuer),
                new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                    original.revocationSignerPrivateKey(),
                    original.revocationSignerCertificate())));
    return withRevocationSignerProfile(intermediate, defaultRevocationSignerProfile(intermediate));
  }

  private static void addIssuerExtensions(
      JcaX509v3CertificateBuilder builder, PublicKey publicKey, X509Certificate root)
      throws Exception {
    var extensions = new JcaX509ExtensionUtils();
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    builder.addExtension(
        Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(publicKey));
    builder.addExtension(
        Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(root));
    addTrustDomain(builder, "spiffe://" + INSTALLATION_ID);
  }

  private static RevocationSignerProfile defaultRevocationSignerProfile(
      CertificateAuthorityMaterial original) {
    var certificate = original.revocationSignerCertificate();
    return new RevocationSignerProfile(
        new SigningIdentity(
            original.revocationSignerPrivateKey(),
            certificate.getPublicKey(),
            new X500Name(certificate.getSubjectX500Principal().getName())),
        original.issuerCertificate(),
        new CertificateShape(certificate.getSerialNumber(), certificate.getNotAfter()));
  }

  private static CertificateAuthorityMaterial withRevocationSignerProfile(
      CertificateAuthorityMaterial original, RevocationSignerProfile profile) throws Exception {
    var originalSigner = original.revocationSignerCertificate();
    var builder =
        new JcaX509v3CertificateBuilder(
            new X500Name(profile.issuerCertificate().getSubjectX500Principal().getName()),
            profile.shape().serialNumber(),
            originalSigner.getNotBefore(),
            profile.shape().notAfter(),
            profile.identity().subject(),
            profile.identity().publicKey());
    addRevocationSignerExtensions(
        builder, profile.identity().publicKey(), profile.issuerCertificate());
    var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(original.issuerPrivateKey());
    var rebuiltSigner = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    return new CertificateAuthorityMaterial(
        INSTALLATION_ID,
        new CertificateAuthorityMaterial.AuthorityChainMaterial(
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.rootPrivateKey(), original.rootCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.issuerPrivateKey(), profile.issuerCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                profile.identity().privateKey(), rebuiltSigner)));
  }

  private static void addRevocationSignerExtensions(
      JcaX509v3CertificateBuilder builder, PublicKey publicKey, X509Certificate issuer)
      throws Exception {
    var extensions = new JcaX509ExtensionUtils();
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
    builder.addExtension(
        Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(publicKey));
    builder.addExtension(
        Extension.authorityKeyIdentifier, false, extensions.createAuthorityKeyIdentifier(issuer));
    builder.addExtension(
        Extension.extendedKeyUsage,
        true,
        new ExtendedKeyUsage(
            KeyPurposeId.getInstance(
                new ASN1ObjectIdentifier(BuiltInCertificateAuthority.REVOCATION_SIGNING_EKU_OID))));
  }

  private static X509Certificate signRoot(
      JcaX509v3CertificateBuilder builder, CertificateAuthorityMaterial original) throws Exception {
    var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(original.rootPrivateKey());
    return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
  }

  private static CertificateAuthorityMaterial withRootCertificate(
      CertificateAuthorityMaterial original, X509Certificate rootCertificate) {
    return new CertificateAuthorityMaterial(
        INSTALLATION_ID,
        new CertificateAuthorityMaterial.AuthorityChainMaterial(
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.rootPrivateKey(), rootCertificate),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.issuerPrivateKey(), original.issuerCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.revocationSignerPrivateKey(), original.revocationSignerCertificate())));
  }

  private static CertificateAuthorityMaterial withRootPrivateKey(
      CertificateAuthorityMaterial original, java.security.PrivateKey rootPrivateKey) {
    return new CertificateAuthorityMaterial(
        INSTALLATION_ID,
        new CertificateAuthorityMaterial.AuthorityChainMaterial(
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                rootPrivateKey, original.rootCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.issuerPrivateKey(), original.issuerCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.revocationSignerPrivateKey(), original.revocationSignerCertificate())));
  }

  private static CertificateAuthorityMaterial withInstallationId(
      CertificateAuthorityMaterial original, UUID installationId) {
    return new CertificateAuthorityMaterial(
        installationId,
        new CertificateAuthorityMaterial.AuthorityChainMaterial(
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.rootPrivateKey(), original.rootCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.issuerPrivateKey(), original.issuerCertificate()),
            new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                original.revocationSignerPrivateKey(), original.revocationSignerCertificate())));
  }

  private static boolean[] keyUsages(int... enabledIndexes) {
    var usages = new boolean[9];
    for (var index : enabledIndexes) {
      usages[index] = true;
    }
    return usages;
  }

  private static List<URI> uriSubjectAlternativeNames(java.security.cert.X509Certificate cert)
      throws Exception {
    return cert.getSubjectAlternativeNames().stream()
        .filter(entry -> entry.getFirst().equals(6))
        .map(entry -> URI.create((String) entry.get(1)))
        .toList();
  }

  private static byte[] sha256(byte[] value) throws Exception {
    return MessageDigest.getInstance("SHA-256").digest(value);
  }

  private record RootExtensions(
      SubjectKeyIdentifier subjectKeyIdentifier,
      AuthorityKeyIdentifier authorityKeyIdentifier,
      String trustDomain) {}

  private record IssuerProfile(BigInteger serialNumber, X500Name subject) {}

  private record CertificateShape(BigInteger serialNumber, Date notAfter) {}

  private record SigningIdentity(PrivateKey privateKey, PublicKey publicKey, X500Name subject) {}

  private record RevocationSignerProfile(
      SigningIdentity identity, X509Certificate issuerCertificate, CertificateShape shape) {

    private RevocationSignerProfile withIdentity(SigningIdentity replacement) {
      return new RevocationSignerProfile(replacement, issuerCertificate, shape);
    }

    private RevocationSignerProfile withShape(CertificateShape replacement) {
      return new RevocationSignerProfile(identity, issuerCertificate, replacement);
    }
  }
}
