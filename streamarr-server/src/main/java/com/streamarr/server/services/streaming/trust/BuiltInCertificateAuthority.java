package com.streamarr.server.services.streaming.trust;

import java.math.BigInteger;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

public final class BuiltInCertificateAuthority {

  static final String REVOCATION_SIGNING_EKU_OID = "2.25.191220049025548712607526882177286901536";

  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
  static final Duration VALIDITY_BACKDATE = Duration.ofMinutes(5);
  static final Duration ROOT_VALIDITY = Duration.ofDays(3650);
  static final Duration ISSUER_VALIDITY = Duration.ofDays(365);
  static final Duration REVOCATION_SIGNER_VALIDITY = Duration.ofDays(30);

  private final SecureRandom secureRandom;

  public BuiltInCertificateAuthority() {
    this(new SecureRandom());
  }

  BuiltInCertificateAuthority(SecureRandom secureRandom) {
    this.secureRandom = secureRandom;
  }

  public void validate(
      CertificateAuthorityMaterial material, UUID installationId, Instant databaseTime) {
    new CertificateAuthorityValidator().validate(material, installationId, databaseTime);
  }

  public CertificateAuthorityMaterial create(UUID installationId, Instant issuedAt) {
    try {
      var rootKeyPair = generateKeyPair();
      var root = createRoot(installationId, rootKeyPair, issuedAt);
      var issuerKeyPair = generateKeyPair();
      var issuer = createIssuer(installationId, issuerKeyPair, rootKeyPair, root, issuedAt);
      var signerKeyPair = generateKeyPair();
      var revocationSigner =
          createRevocationSigner(installationId, signerKeyPair, issuerKeyPair, issuer, issuedAt);

      root.verify(root.getPublicKey());
      issuer.verify(root.getPublicKey());
      revocationSigner.verify(issuer.getPublicKey());

      var material =
          new CertificateAuthorityMaterial(
              installationId,
              new CertificateAuthorityMaterial.AuthorityChainMaterial(
                  new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                      rootKeyPair.getPrivate(), root),
                  new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                      issuerKeyPair.getPrivate(), issuer),
                  new CertificateAuthorityMaterial.AuthorityKeyMaterial(
                      signerKeyPair.getPrivate(), revocationSigner)));
      validate(material, installationId, issuedAt);
      return material;
    } catch (GeneralSecurityException | OperatorCreationException | CertIOException e) {
      throw new IllegalStateException("Failed to create the installation certificate authority", e);
    }
  }

  private X509Certificate createRoot(UUID installationId, KeyPair keyPair, Instant issuedAt)
      throws GeneralSecurityException, OperatorCreationException, CertIOException {
    var subject = subject(installationId, "Root CA");
    var builder =
        certificateBuilder(subject, subject, keyPair, issuedAt, issuedAt.plus(ROOT_VALIDITY));
    var extensionUtils = extensionUtils();
    addSigningExtensions(
        builder,
        new BasicConstraints(1),
        extensionUtils.createAuthorityKeyIdentifier(keyPair.getPublic()),
        extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()),
        installationId);
    return sign(builder, keyPair);
  }

  private X509Certificate createIssuer(
      UUID installationId,
      KeyPair keyPair,
      KeyPair rootKeyPair,
      X509Certificate root,
      Instant issuedAt)
      throws GeneralSecurityException, OperatorCreationException, CertIOException {
    var builder =
        certificateBuilder(
            new X500Name(root.getSubjectX500Principal().getName()),
            subject(installationId, "Issuing CA"),
            keyPair,
            issuedAt,
            issuedAt.plus(ISSUER_VALIDITY));
    var extensionUtils = extensionUtils();
    addSigningExtensions(
        builder,
        new BasicConstraints(0),
        extensionUtils.createAuthorityKeyIdentifier(root),
        extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()),
        installationId);
    return sign(builder, rootKeyPair);
  }

  private X509Certificate createRevocationSigner(
      UUID installationId,
      KeyPair keyPair,
      KeyPair issuerKeyPair,
      X509Certificate issuer,
      Instant issuedAt)
      throws GeneralSecurityException, OperatorCreationException, CertIOException {
    var builder =
        certificateBuilder(
            new X500Name(issuer.getSubjectX500Principal().getName()),
            subject(installationId, "Revocation Signer"),
            keyPair,
            issuedAt,
            issuedAt.plus(REVOCATION_SIGNER_VALIDITY));
    var extensionUtils = extensionUtils();
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
    builder.addExtension(
        Extension.subjectKeyIdentifier,
        false,
        extensionUtils.createSubjectKeyIdentifier(keyPair.getPublic()));
    builder.addExtension(
        Extension.authorityKeyIdentifier,
        false,
        extensionUtils.createAuthorityKeyIdentifier(issuer));
    builder.addExtension(
        Extension.extendedKeyUsage,
        true,
        new ExtendedKeyUsage(
            KeyPurposeId.getInstance(new ASN1ObjectIdentifier(REVOCATION_SIGNING_EKU_OID))));
    return sign(builder, issuerKeyPair);
  }

  private void addSigningExtensions(
      JcaX509v3CertificateBuilder builder,
      BasicConstraints basicConstraints,
      org.bouncycastle.asn1.x509.AuthorityKeyIdentifier authorityKeyIdentifier,
      org.bouncycastle.asn1.x509.SubjectKeyIdentifier subjectKeyIdentifier,
      UUID installationId)
      throws CertIOException {
    builder.addExtension(Extension.basicConstraints, true, basicConstraints);
    builder.addExtension(
        Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
    builder.addExtension(Extension.subjectKeyIdentifier, false, subjectKeyIdentifier);
    builder.addExtension(Extension.authorityKeyIdentifier, false, authorityKeyIdentifier);
    builder.addExtension(
        Extension.subjectAlternativeName,
        false,
        new GeneralNames(
            new GeneralName(
                GeneralName.uniformResourceIdentifier, trustDomain(installationId).toString())));
  }

  private JcaX509v3CertificateBuilder certificateBuilder(
      X500Name issuer,
      X500Name subject,
      KeyPair subjectKeyPair,
      Instant issuedAt,
      Instant expiresAt) {
    return new JcaX509v3CertificateBuilder(
        issuer,
        positiveSerial(),
        Date.from(issuedAt.minus(VALIDITY_BACKDATE)),
        Date.from(expiresAt),
        subject,
        subjectKeyPair.getPublic());
  }

  private X509Certificate sign(JcaX509v3CertificateBuilder builder, KeyPair signingKeyPair)
      throws OperatorCreationException, CertificateException {
    var signer =
        new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .setSecureRandom(secureRandom)
            .build(signingKeyPair.getPrivate());
    return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
  }

  private KeyPair generateKeyPair() throws GeneralSecurityException {
    var generator = KeyPairGenerator.getInstance("EC");
    generator.initialize(new ECGenParameterSpec("secp256r1"), secureRandom);
    return generator.generateKeyPair();
  }

  private BigInteger positiveSerial() {
    BigInteger serial;
    do {
      serial = new BigInteger(128, secureRandom);
    } while (serial.signum() <= 0);
    return serial;
  }

  private JcaX509ExtensionUtils extensionUtils() throws GeneralSecurityException {
    return new JcaX509ExtensionUtils();
  }

  private static X500Name subject(UUID installationId, String role) {
    return new X500Name(subjectName(installationId, role));
  }

  static String subjectName(UUID installationId, String role) {
    return "CN=Streamarr Installation " + installationId + " " + role;
  }

  private static URI trustDomain(UUID installationId) {
    return URI.create("spiffe://" + installationId.toString().toLowerCase(java.util.Locale.ROOT));
  }
}
