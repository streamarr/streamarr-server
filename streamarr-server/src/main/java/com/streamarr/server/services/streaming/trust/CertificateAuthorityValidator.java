package com.streamarr.server.services.streaming.trust;

import java.io.IOException;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x509.AuthorityKeyIdentifier;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;

final class CertificateAuthorityValidator {

  private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
  private static final String NORMALIZED_SIGNATURE_ALGORITHM =
      SIGNATURE_ALGORITHM.toUpperCase(Locale.ROOT);
  private static final byte[] KEY_MATCH_CHALLENGE =
      "streamarr-ca-key-pair-validation-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

  void validate(
      CertificateAuthorityMaterial material, UUID expectedInstallationId, Instant databaseTime) {
    if (!material.installationId().equals(expectedInstallationId)) {
      throw new InstallationTrustException(
          "Certificate authority belongs to a different installation");
    }

    try {
      var root = material.rootCertificate();
      var issuer = material.issuerCertificate();
      var revocationSigner = material.revocationSignerCertificate();
      root.verify(root.getPublicKey());
      issuer.verify(root.getPublicKey());
      revocationSigner.verify(issuer.getPublicKey());
      root.checkValidity(Date.from(databaseTime));
      issuer.checkValidity(Date.from(databaseTime));
      revocationSigner.checkValidity(Date.from(databaseTime));

      requireSigningCertificate(root, 1, expectedInstallationId);
      requireSigningCertificate(issuer, 0, expectedInstallationId);
      requireRevocationSigner(revocationSigner);
      requireKeyIdentifiers(root, issuer, revocationSigner);
      requireEqual(root.getSerialNumber().equals(issuer.getSerialNumber()), false);
      requireP256(material.rootPrivateKey(), root);
      requireP256(material.issuerPrivateKey(), issuer);
      requireP256(material.revocationSignerPrivateKey(), revocationSigner);
      requireDistinctAuthorityKeys(root, issuer, revocationSigner);
      requireMatchingKey(material.rootPrivateKey(), root);
      requireMatchingKey(material.issuerPrivateKey(), issuer);
      requireMatchingKey(material.revocationSignerPrivateKey(), revocationSigner);
      requireValidityNesting(root, issuer, revocationSigner);
      requireMaximumValidity(
          root,
          BuiltInCertificateAuthority.ROOT_VALIDITY.plus(
              BuiltInCertificateAuthority.VALIDITY_BACKDATE));
      requireMaximumValidity(
          issuer,
          BuiltInCertificateAuthority.ISSUER_VALIDITY.plus(
              BuiltInCertificateAuthority.VALIDITY_BACKDATE));
      requireMaximumValidity(
          revocationSigner,
          BuiltInCertificateAuthority.REVOCATION_SIGNER_VALIDITY.plus(
              BuiltInCertificateAuthority.VALIDITY_BACKDATE));

      var rootSubject = expectedSubject(expectedInstallationId, "Root CA");
      var issuerSubject = expectedSubject(expectedInstallationId, "Issuing CA");
      var revocationSubject = expectedSubject(expectedInstallationId, "Revocation Signer");
      requireEqual(root.getSubjectX500Principal(), rootSubject);
      requireEqual(root.getIssuerX500Principal(), rootSubject);
      requireEqual(issuer.getSubjectX500Principal(), issuerSubject);
      requireEqual(issuer.getIssuerX500Principal(), rootSubject);
      requireEqual(revocationSigner.getSubjectX500Principal(), revocationSubject);
      requireEqual(revocationSigner.getIssuerX500Principal(), issuerSubject);
    } catch (InstallationTrustException e) {
      throw e;
    } catch (Exception e) {
      throw new InstallationTrustException("Certificate authority profile is invalid", e);
    }
  }

  private void requireSigningCertificate(
      X509Certificate certificate, int pathLength, UUID installationId)
      throws CertificateParsingException {
    requireEqual(certificate.getBasicConstraints(), pathLength);
    requireEqual(certificate.getKeyUsage(), keyUsages(5, 6));
    requireEqual(certificate.getExtendedKeyUsage(), null);
    requireEqual(
        certificate.getSigAlgName().toUpperCase(Locale.ROOT), NORMALIZED_SIGNATURE_ALGORITHM);
    requireEqual(
        certificate.getCriticalExtensionOIDs(),
        Set.of(Extension.basicConstraints.getId(), Extension.keyUsage.getId()));
    requireEqual(
        certificate.getNonCriticalExtensionOIDs(),
        Set.of(
            Extension.subjectKeyIdentifier.getId(),
            Extension.authorityKeyIdentifier.getId(),
            Extension.subjectAlternativeName.getId()));
    requireEqual(uriSubjectAlternativeNames(certificate), List.of("spiffe://" + installationId));
    requireCommonExtensions(certificate);
  }

  private void requireRevocationSigner(X509Certificate certificate)
      throws CertificateParsingException {
    requireEqual(certificate.getBasicConstraints(), -1);
    requireEqual(certificate.getKeyUsage(), keyUsages(0));
    requireEqual(certificate.getSubjectAlternativeNames(), null);
    requireEqual(
        certificate.getExtendedKeyUsage(),
        List.of(BuiltInCertificateAuthority.REVOCATION_SIGNING_EKU_OID));
    requireEqual(
        certificate.getSigAlgName().toUpperCase(Locale.ROOT), NORMALIZED_SIGNATURE_ALGORITHM);
    requireEqual(
        certificate.getCriticalExtensionOIDs(),
        Set.of(
            Extension.basicConstraints.getId(),
            Extension.keyUsage.getId(),
            Extension.extendedKeyUsage.getId()));
    requireEqual(
        certificate.getNonCriticalExtensionOIDs(),
        Set.of(Extension.subjectKeyIdentifier.getId(), Extension.authorityKeyIdentifier.getId()));
    requireCommonExtensions(certificate);
  }

  private void requireCommonExtensions(X509Certificate certificate) {
    Objects.requireNonNull(certificate.getExtensionValue(Extension.subjectKeyIdentifier.getId()));
    Objects.requireNonNull(certificate.getExtensionValue(Extension.authorityKeyIdentifier.getId()));
    requireEqual(certificate.getSerialNumber().signum(), 1);
    requireMaximum(certificate.getSerialNumber().bitLength(), 128);
  }

  private void requireP256(java.security.PrivateKey privateKey, X509Certificate certificate)
      throws GeneralSecurityException {
    var ecPrivateKey = requireEcPrivateKey(privateKey);
    var ecPublicKey = requireEcPublicKey(certificate);
    var expected = expectedP256Parameters();
    requireP256Parameters(ecPrivateKey.getParams(), expected);
    requireP256Parameters(ecPublicKey.getParams(), expected);
  }

  private void requireKeyIdentifiers(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner)
      throws GeneralSecurityException, IOException {
    var extensionUtils = new JcaX509ExtensionUtils();
    var rootIdentifier =
        extensionUtils.createSubjectKeyIdentifier(root.getPublicKey()).getKeyIdentifier();
    var issuerIdentifier =
        extensionUtils.createSubjectKeyIdentifier(issuer.getPublicKey()).getKeyIdentifier();
    var revocationSignerIdentifier =
        extensionUtils
            .createSubjectKeyIdentifier(revocationSigner.getPublicKey())
            .getKeyIdentifier();
    var rootAuthorityIdentifier = extensionUtils.createAuthorityKeyIdentifier(root.getPublicKey());
    var issuerAuthorityIdentifier = extensionUtils.createAuthorityKeyIdentifier(root);
    var revocationAuthorityIdentifier = extensionUtils.createAuthorityKeyIdentifier(issuer);

    requireIdentifier(subjectKeyIdentifier(root), rootIdentifier);
    requireAuthorityIdentifier(authorityKeyIdentifier(root), rootAuthorityIdentifier);
    requireIdentifier(subjectKeyIdentifier(issuer), issuerIdentifier);
    requireAuthorityIdentifier(authorityKeyIdentifier(issuer), issuerAuthorityIdentifier);
    requireIdentifier(subjectKeyIdentifier(revocationSigner), revocationSignerIdentifier);
    requireAuthorityIdentifier(
        authorityKeyIdentifier(revocationSigner), revocationAuthorityIdentifier);
  }

  private byte[] subjectKeyIdentifier(X509Certificate certificate) throws IOException {
    return SubjectKeyIdentifier.getInstance(
            decodedExtensionValue(certificate, Extension.subjectKeyIdentifier.getId()))
        .getKeyIdentifier();
  }

  private AuthorityKeyIdentifier authorityKeyIdentifier(X509Certificate certificate)
      throws IOException {
    return AuthorityKeyIdentifier.getInstance(
        decodedExtensionValue(certificate, Extension.authorityKeyIdentifier.getId()));
  }

  private ASN1Primitive decodedExtensionValue(X509Certificate certificate, String oid)
      throws IOException {
    var encoded = ASN1OctetString.getInstance(certificate.getExtensionValue(oid)).getOctets();
    return ASN1Primitive.fromByteArray(encoded);
  }

  private void requireIdentifier(byte[] actual, byte[] expected) {
    if (!MessageDigest.isEqual(actual, expected)) {
      throw invalidProfile();
    }
  }

  private void requireAuthorityIdentifier(
      AuthorityKeyIdentifier actual, AuthorityKeyIdentifier expected) throws IOException {
    requireIdentifier(actual.getEncoded(), expected.getEncoded());
  }

  private void requireDistinctAuthorityKeys(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner) {
    var rootPoint = ((ECPublicKey) root.getPublicKey()).getW();
    var issuerPoint = ((ECPublicKey) issuer.getPublicKey()).getW();
    var revocationSignerPoint = ((ECPublicKey) revocationSigner.getPublicKey()).getW();
    requireEqual(rootPoint.equals(issuerPoint), false);
    requireEqual(rootPoint.equals(revocationSignerPoint), false);
    requireEqual(issuerPoint.equals(revocationSignerPoint), false);
  }

  private void requireMatchingKey(java.security.PrivateKey privateKey, X509Certificate certificate)
      throws GeneralSecurityException {
    var signer = Signature.getInstance(SIGNATURE_ALGORITHM);
    signer.initSign(privateKey);
    signer.update(KEY_MATCH_CHALLENGE);
    var signature = signer.sign();

    var verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
    verifier.initVerify(certificate.getPublicKey());
    verifier.update(KEY_MATCH_CHALLENGE);
    if (!verifier.verify(signature)) {
      throw new InstallationTrustException(
          "Certificate authority contains a mismatched private key");
    }
  }

  private void requireValidityNesting(
      X509Certificate root, X509Certificate issuer, X509Certificate revocationSigner) {
    requireEqual(root.getNotBefore(), issuer.getNotBefore());
    requireEqual(root.getNotBefore(), revocationSigner.getNotBefore());
    requireEqual(issuer.getNotAfter().after(root.getNotAfter()), false);
    requireEqual(revocationSigner.getNotAfter().after(issuer.getNotAfter()), false);
  }

  private void requireMaximumValidity(X509Certificate certificate, Duration maximum) {
    var actual =
        Duration.between(
            certificate.getNotBefore().toInstant(), certificate.getNotAfter().toInstant());
    if (actual.compareTo(maximum) > 0) {
      throw invalidProfile();
    }
  }

  private X500Principal expectedSubject(UUID installationId, String role) {
    return new X500Principal(BuiltInCertificateAuthority.subjectName(installationId, role));
  }

  private ECParameterSpec expectedP256Parameters() throws GeneralSecurityException {
    var parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    return parameters.getParameterSpec(ECParameterSpec.class);
  }

  private ECPrivateKey requireEcPrivateKey(java.security.PrivateKey privateKey) {
    if (privateKey instanceof ECPrivateKey ecPrivateKey) {
      return ecPrivateKey;
    }
    throw invalidProfile();
  }

  private ECPublicKey requireEcPublicKey(X509Certificate certificate) {
    if (certificate.getPublicKey() instanceof ECPublicKey ecPublicKey) {
      return ecPublicKey;
    }
    throw invalidProfile();
  }

  private void requireP256Parameters(ECParameterSpec actual, ECParameterSpec expected) {
    requireEqual(actual.getCurve(), expected.getCurve());
    requireEqual(actual.getGenerator(), expected.getGenerator());
    requireEqual(actual.getOrder(), expected.getOrder());
    requireEqual(actual.getCofactor(), expected.getCofactor());
  }

  private List<String> uriSubjectAlternativeNames(X509Certificate certificate)
      throws CertificateParsingException {
    return Optional.ofNullable(certificate.getSubjectAlternativeNames()).orElse(List.of()).stream()
        .map(this::uriSubjectAlternativeName)
        .toList();
  }

  private String uriSubjectAlternativeName(List<?> entry) {
    requireEqual(entry.getFirst(), 6);
    return (String) entry.get(1);
  }

  private boolean[] keyUsages(int... enabledIndexes) {
    var usages = new boolean[9];
    for (var index : enabledIndexes) {
      usages[index] = true;
    }
    return usages;
  }

  private void requireEqual(Object actual, Object expected) {
    if (!Objects.deepEquals(actual, expected)) {
      throw invalidProfile();
    }
  }

  private void requireMaximum(int actual, int maximum) {
    if (actual > maximum) {
      throw invalidProfile();
    }
  }

  private InstallationTrustException invalidProfile() {
    return new InstallationTrustException("Certificate authority profile is invalid");
  }
}
