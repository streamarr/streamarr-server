package com.streamarr.server.services.streaming.trust;

import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.security.auth.x500.X500Principal;
import lombok.Builder;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.springframework.stereotype.Component;

@Component
public final class WorkerCertificateValidator {

  private static final String SHA256_WITH_ECDSA_OID = "1.2.840.10045.4.3.2";
  private static final Set<String> CRITICAL_EXTENSIONS =
      Set.of(
          Extension.basicConstraints.getId(),
          Extension.keyUsage.getId(),
          Extension.extendedKeyUsage.getId());
  private static final Set<String> NON_CRITICAL_EXTENSIONS =
      Set.of(
          Extension.subjectKeyIdentifier.getId(),
          Extension.authorityKeyIdentifier.getId(),
          Extension.subjectAlternativeName.getId());
  private static final Set<String> EXTENDED_KEY_USAGE =
      Set.of(KeyPurposeId.id_kp_clientAuth.getId(), KeyPurposeId.id_kp_serverAuth.getId());

  public boolean matches(EncodedWorkerCertificate encodedCertificate, ValidationContext context) {
    Objects.requireNonNull(encodedCertificate);
    Objects.requireNonNull(context);
    return switch (context.parameters().profile()) {
      case V1 -> matchesV1(encodedCertificate.x509Certificate(), context);
    };
  }

  private boolean matchesV1(X509Certificate certificate, ValidationContext context) {
    return certificate.getVersion() == 3
        && certificate.getIssuerUniqueID() == null
        && certificate.getSubjectUniqueID() == null
        && matchesFixedClaim(certificate, context)
        && matchesNames(certificate, context)
        && matchesIssuer(certificate, context)
        && matchesExtensionSet(certificate, context);
  }

  private boolean matchesFixedClaim(X509Certificate certificate, ValidationContext context) {
    var parameters = context.parameters();
    return MessageDigest.isEqual(
            certificate.getPublicKey().getEncoded(), context.subjectPublicKeyInfo().der())
        && certificate.getSerialNumber().equals(parameters.serialNumber().value())
        && certificate.getNotBefore().toInstant().equals(parameters.validity().notBefore())
        && certificate.getNotAfter().toInstant().equals(parameters.validity().notAfter());
  }

  private boolean matchesNames(X509Certificate certificate, ValidationContext context) {
    var expectedSubject = new X500Principal("CN=Streamarr Worker " + context.workerId());
    if (!certificate.getSubjectX500Principal().equals(expectedSubject)) {
      return false;
    }
    try {
      var names = certificate.getSubjectAlternativeNames();
      if (names == null || names.size() != 1) {
        return false;
      }
      var name = names.iterator().next();
      var expected =
          URI.create(
                  "spiffe://"
                      + context.installationId()
                      + "/streamarr/worker/"
                      + context.workerId())
              .toString();
      return name.size() >= 2
          && Integer.valueOf(6).equals(name.getFirst())
          && expected.equals(name.get(1));
    } catch (CertificateParsingException _) {
      return false;
    }
  }

  private boolean matchesIssuer(X509Certificate certificate, ValidationContext context) {
    var issuer = context.issuer();
    if (!SHA256_WITH_ECDSA_OID.equals(certificate.getSigAlgOID())
        || !certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())
        || !matchesIssuerDigest(issuer, context.parameters().issuerCertificateSha256())
        || certificate.getNotBefore().before(issuer.getNotBefore())
        || certificate.getNotAfter().after(issuer.getNotAfter())) {
      return false;
    }
    try {
      certificate.verify(issuer.getPublicKey());
      return true;
    } catch (GeneralSecurityException _) {
      return false;
    }
  }

  private boolean matchesIssuerDigest(X509Certificate issuer, Sha256Digest expected) {
    try {
      var actual =
          new Sha256Digest(MessageDigest.getInstance("SHA-256").digest(issuer.getEncoded()));
      return actual.equals(expected);
    } catch (CertificateEncodingException | java.security.NoSuchAlgorithmException _) {
      return false;
    }
  }

  private boolean matchesExtensionSet(X509Certificate certificate, ValidationContext context) {
    return CRITICAL_EXTENSIONS.equals(certificate.getCriticalExtensionOIDs())
        && NON_CRITICAL_EXTENSIONS.equals(certificate.getNonCriticalExtensionOIDs())
        && certificate.getBasicConstraints() == -1
        && matchesKeyUsage(certificate.getKeyUsage())
        && matchesExtendedKeyUsage(certificate)
        && matchesSubjectKeyIdentifier(certificate)
        && matchesAuthorityKeyIdentifier(certificate, context.issuer());
  }

  private boolean matchesKeyUsage(boolean[] keyUsage) {
    if (keyUsage == null || keyUsage.length == 0 || !keyUsage[0]) {
      return false;
    }
    for (var index = 1; index < keyUsage.length; index++) {
      if (keyUsage[index]) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesExtendedKeyUsage(X509Certificate certificate) {
    try {
      var usage = certificate.getExtendedKeyUsage();
      return usage != null
          && usage.size() == EXTENDED_KEY_USAGE.size()
          && EXTENDED_KEY_USAGE.equals(Set.copyOf(usage));
    } catch (CertificateParsingException _) {
      return false;
    }
  }

  private boolean matchesSubjectKeyIdentifier(X509Certificate certificate) {
    try {
      var expected =
          new JcaX509ExtensionUtils()
              .createSubjectKeyIdentifier(certificate.getPublicKey())
              .getEncoded();
      return MessageDigest.isEqual(
          expected, extensionPayload(certificate, Extension.subjectKeyIdentifier.getId()));
    } catch (GeneralSecurityException | IOException | IllegalArgumentException _) {
      return false;
    }
  }

  private boolean matchesAuthorityKeyIdentifier(
      X509Certificate certificate, X509Certificate issuer) {
    try {
      var expected = new JcaX509ExtensionUtils().createAuthorityKeyIdentifier(issuer).getEncoded();
      return MessageDigest.isEqual(
          expected, extensionPayload(certificate, Extension.authorityKeyIdentifier.getId()));
    } catch (GeneralSecurityException | IOException | IllegalArgumentException _) {
      return false;
    }
  }

  private byte[] extensionPayload(X509Certificate certificate, String oid) {
    return ASN1OctetString.getInstance(certificate.getExtensionValue(oid)).getOctets();
  }

  @Builder
  public record ValidationContext(
      UUID installationId,
      UUID workerId,
      SubjectPublicKeyInfo subjectPublicKeyInfo,
      CertificateIssuanceParameters parameters,
      X509Certificate issuer) {

    public ValidationContext {
      Objects.requireNonNull(installationId);
      Objects.requireNonNull(workerId);
      Objects.requireNonNull(subjectPublicKeyInfo);
      Objects.requireNonNull(parameters);
      Objects.requireNonNull(issuer);
    }
  }
}
