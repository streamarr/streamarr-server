package com.streamarr.server.services.streaming.trust;

import java.net.URI;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
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

public final class WorkerCertificateTestFixture {

  private WorkerCertificateTestFixture() {}

  public static CertificateShape.CertificateShapeBuilder validShape(WorkerContext context)
      throws Exception {
    Objects.requireNonNull(context);
    var extensionUtils = new JcaX509ExtensionUtils();
    return CertificateShape.builder()
        .context(context)
        .issuerName(
            new X500Name(
                context
                    .authorityMaterial()
                    .issuerCertificate()
                    .getSubjectX500Principal()
                    .getName()))
        .subjectName(new X500Name("CN=Streamarr Worker " + context.workerId()))
        .subjectAlternativeNames(
            new GeneralNames(
                new GeneralName(
                    GeneralName.uniformResourceIdentifier,
                    URI.create(
                            "spiffe://"
                                + context.installationId()
                                + "/streamarr/worker/"
                                + context.workerId())
                        .toString())))
        .basicConstraints(new BasicConstraints(false))
        .basicConstraintsCritical(true)
        .keyUsage(new KeyUsage(KeyUsage.digitalSignature))
        .keyUsageCritical(true)
        .extendedKeyUsage(
            new ExtendedKeyUsage(
                new KeyPurposeId[] {KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_serverAuth}))
        .extendedKeyUsageCritical(true)
        .subjectKeyIdentifier(extensionUtils.createSubjectKeyIdentifier(context.subjectPublicKey()))
        .authorityKeyIdentifier(
            extensionUtils.createAuthorityKeyIdentifier(
                context.authorityMaterial().issuerCertificate()))
        .signatureAlgorithm("SHA256withECDSA")
        .additionalExtensions(List.of());
  }

  public static X509Certificate certificate(CertificateShape shape) throws Exception {
    Objects.requireNonNull(shape);
    var context = shape.context();
    var parameters = context.parameters();
    var builder =
        new JcaX509v3CertificateBuilder(
            shape.issuerName(),
            parameters.serialNumber().value(),
            Date.from(parameters.validity().notBefore()),
            Date.from(parameters.validity().notAfter()),
            shape.subjectName(),
            context.subjectPublicKey());
    if (shape.issuerUniqueId()) {
      builder.setIssuerUniqueID(new boolean[] {true});
    }
    if (shape.subjectUniqueId()) {
      builder.setSubjectUniqueID(new boolean[] {true});
    }
    addExtensions(builder, shape);
    var signer =
        new JcaContentSignerBuilder(shape.signatureAlgorithm())
            .build(context.authorityMaterial().issuerPrivateKey());
    return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
  }

  private static void addExtensions(JcaX509v3CertificateBuilder builder, CertificateShape shape)
      throws Exception {
    if (shape.basicConstraints() != null) {
      builder.addExtension(
          Extension.basicConstraints, shape.basicConstraintsCritical(), shape.basicConstraints());
    }
    if (shape.keyUsage() != null) {
      builder.addExtension(Extension.keyUsage, shape.keyUsageCritical(), shape.keyUsage());
    }
    if (shape.extendedKeyUsage() != null) {
      builder.addExtension(
          Extension.extendedKeyUsage, shape.extendedKeyUsageCritical(), shape.extendedKeyUsage());
    }
    if (shape.subjectKeyIdentifier() != null) {
      builder.addExtension(
          Extension.subjectKeyIdentifier,
          shape.subjectKeyIdentifierCritical(),
          shape.subjectKeyIdentifier());
    }
    if (shape.authorityKeyIdentifier() != null) {
      builder.addExtension(
          Extension.authorityKeyIdentifier,
          shape.authorityKeyIdentifierCritical(),
          shape.authorityKeyIdentifier());
    }
    if (shape.subjectAlternativeNames() != null) {
      builder.addExtension(
          Extension.subjectAlternativeName,
          shape.subjectAlternativeNamesCritical(),
          shape.subjectAlternativeNames());
    }
    for (var extension : shape.additionalExtensions()) {
      builder.addExtension(extension.oid(), extension.critical(), extension.value());
    }
  }

  @Builder
  public record WorkerContext(
      UUID installationId,
      UUID workerId,
      CertificateAuthorityMaterial authorityMaterial,
      PublicKey subjectPublicKey,
      CertificateIssuanceParameters parameters) {

    public WorkerContext {
      Objects.requireNonNull(installationId);
      Objects.requireNonNull(workerId);
      Objects.requireNonNull(authorityMaterial);
      Objects.requireNonNull(subjectPublicKey);
      Objects.requireNonNull(parameters);
    }
  }

  @Builder(toBuilder = true)
  public record CertificateShape(
      WorkerContext context,
      X500Name issuerName,
      X500Name subjectName,
      boolean issuerUniqueId,
      boolean subjectUniqueId,
      GeneralNames subjectAlternativeNames,
      boolean subjectAlternativeNamesCritical,
      BasicConstraints basicConstraints,
      boolean basicConstraintsCritical,
      KeyUsage keyUsage,
      boolean keyUsageCritical,
      ExtendedKeyUsage extendedKeyUsage,
      boolean extendedKeyUsageCritical,
      SubjectKeyIdentifier subjectKeyIdentifier,
      boolean subjectKeyIdentifierCritical,
      AuthorityKeyIdentifier authorityKeyIdentifier,
      boolean authorityKeyIdentifierCritical,
      String signatureAlgorithm,
      List<AdditionalExtension> additionalExtensions) {

    public CertificateShape {
      Objects.requireNonNull(context);
      Objects.requireNonNull(issuerName);
      Objects.requireNonNull(subjectName);
      Objects.requireNonNull(signatureAlgorithm);
      additionalExtensions = List.copyOf(additionalExtensions);
    }
  }

  public record AdditionalExtension(
      ASN1ObjectIdentifier oid, boolean critical, ASN1Encodable value) {

    public AdditionalExtension {
      Objects.requireNonNull(oid);
      Objects.requireNonNull(value);
    }
  }
}
