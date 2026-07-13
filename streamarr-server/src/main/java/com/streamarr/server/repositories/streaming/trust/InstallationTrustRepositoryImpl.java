package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeActiveTrustBundle.TRANSCODE_ACTIVE_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeCaSigningLease.TRANSCODE_CA_SIGNING_LEASE;
import static com.streamarr.server.jooq.generated.tables.TranscodeInstallation.TRANSCODE_INSTALLATION;
import static com.streamarr.server.jooq.generated.tables.TranscodePublicTrustBundle.TRANSCODE_PUBLIC_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeTrustCertificate.TRANSCODE_TRUST_CERTIFICATE;

import com.streamarr.server.jooq.generated.enums.TranscodeTrustCertificateKind;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.InitialTrustPublication;
import com.streamarr.server.services.streaming.trust.InstallationTrust;
import com.streamarr.server.services.streaming.trust.InstallationTrustException;
import com.streamarr.server.services.streaming.trust.PublicTrustBundle;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InstallationTrustRepositoryImpl implements InstallationTrustRepository {

  private static final long INITIAL_BUNDLE_VERSION = 1L;

  private final DSLContext dsl;

  @Override
  public UUID installationId() {
    return dsl.select(TRANSCODE_INSTALLATION.INSTALLATION_ID)
        .from(TRANSCODE_INSTALLATION)
        .where(TRANSCODE_INSTALLATION.SINGLETON.isTrue())
        .fetchSingle(TRANSCODE_INSTALLATION.INSTALLATION_ID);
  }

  @Override
  public Instant databaseTime() {
    return CertificateSigningLeaseGuard.databaseTime(dsl).toInstant();
  }

  @Override
  public Optional<InstallationTrust> findInitialized() {
    var states =
        dsl.select(
                TRANSCODE_INSTALLATION.INSTALLATION_ID,
                TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256,
                TRANSCODE_INSTALLATION.INITIALIZED_AT,
                TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION,
                TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT,
                TRANSCODE_PUBLIC_TRUST_BUNDLE.CREATED_AT,
                TRANSCODE_TRUST_CERTIFICATE.KIND,
                TRANSCODE_TRUST_CERTIFICATE.ORDINAL,
                TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER,
                TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256)
            .from(TRANSCODE_INSTALLATION)
            .join(TRANSCODE_ACTIVE_TRUST_BUNDLE)
            .on(
                TRANSCODE_ACTIVE_TRUST_BUNDLE.INSTALLATION_ID.eq(
                    TRANSCODE_INSTALLATION.INSTALLATION_ID))
            .leftJoin(TRANSCODE_PUBLIC_TRUST_BUNDLE)
            .on(
                TRANSCODE_PUBLIC_TRUST_BUNDLE
                    .INSTALLATION_ID
                    .eq(TRANSCODE_ACTIVE_TRUST_BUNDLE.INSTALLATION_ID)
                    .and(
                        TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION.eq(
                            TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION)))
            .leftJoin(TRANSCODE_TRUST_CERTIFICATE)
            .on(
                TRANSCODE_TRUST_CERTIFICATE
                    .INSTALLATION_ID
                    .eq(TRANSCODE_ACTIVE_TRUST_BUNDLE.INSTALLATION_ID)
                    .and(
                        TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION.eq(
                            TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION)))
            .where(TRANSCODE_INSTALLATION.SINGLETON.isTrue())
            .and(TRANSCODE_ACTIVE_TRUST_BUNDLE.SINGLETON.isTrue())
            .orderBy(TRANSCODE_TRUST_CERTIFICATE.KIND, TRANSCODE_TRUST_CERTIFICATE.ORDINAL)
            .fetch();
    if (states.isEmpty()) {
      throw new InstallationTrustException("Installation trust singleton state is missing");
    }
    var state = states.getFirst();
    var rootDigest = state.value2();
    var persistedState =
        PersistedTrustState.builder()
            .installationId(state.value1())
            .initializedAt(state.value3())
            .bundleVersion(state.value4())
            .activatedAt(state.value5())
            .createdAt(state.value6())
            .build();
    if (persistedState.isEmpty(rootDigest)) {
      return Optional.empty();
    }
    persistedState.requireComplete(rootDigest);

    var bundle = hydrateBundle(persistedState.bundle(), states);
    var containsBootstrapRoot =
        bundle.trustAnchors().stream()
            .anyMatch(certificate -> MessageDigest.isEqual(rootDigest, sha256(certificate)));
    if (!containsBootstrapRoot) {
      throw new InstallationTrustException(
          "Installation root fingerprint does not match the active trust anchor");
    }
    return Optional.of(new InstallationTrust(persistedState.installationId(), rootDigest, bundle));
  }

  @Override
  public Optional<PublicTrustBundle> findBundle(UUID installationId, long version) {
    Objects.requireNonNull(installationId);
    if (version <= 0) {
      throw new IllegalArgumentException("Public trust bundle version must be positive");
    }
    var states =
        dsl.select(
                TRANSCODE_PUBLIC_TRUST_BUNDLE.INSTALLATION_ID,
                TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION,
                TRANSCODE_PUBLIC_TRUST_BUNDLE.CREATED_AT,
                TRANSCODE_TRUST_CERTIFICATE.KIND,
                TRANSCODE_TRUST_CERTIFICATE.ORDINAL,
                TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER,
                TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256)
            .from(TRANSCODE_PUBLIC_TRUST_BUNDLE)
            .leftJoin(TRANSCODE_TRUST_CERTIFICATE)
            .on(
                TRANSCODE_TRUST_CERTIFICATE
                    .INSTALLATION_ID
                    .eq(TRANSCODE_PUBLIC_TRUST_BUNDLE.INSTALLATION_ID)
                    .and(
                        TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION.eq(
                            TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION)))
            .where(TRANSCODE_PUBLIC_TRUST_BUNDLE.INSTALLATION_ID.eq(installationId))
            .and(TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION.eq(version))
            .orderBy(TRANSCODE_TRUST_CERTIFICATE.KIND, TRANSCODE_TRUST_CERTIFICATE.ORDINAL)
            .fetch();
    if (states.isEmpty()) {
      return Optional.empty();
    }
    var state = states.getFirst();
    var persistedBundle =
        new PersistedPublicTrustBundle(state.value1(), state.value2(), state.value3());
    return Optional.of(hydrateBundle(persistedBundle, states));
  }

  private PublicTrustBundle hydrateBundle(
      PersistedPublicTrustBundle persistedBundle,
      Iterable<? extends org.jooq.Record> certificates) {
    var certificatesByRole = CertificateRoles.empty();
    for (var certificate : certificates) {
      addCertificate(persistedBundle, certificatesByRole, certificate);
    }
    validateCertificateRoles(persistedBundle, certificatesByRole);
    return toPublicTrustBundle(persistedBundle, certificatesByRole);
  }

  private void addCertificate(
      PersistedPublicTrustBundle persistedBundle,
      CertificateRoles certificatesByRole,
      org.jooq.Record certificate) {
    var kind = certificate.get(TRANSCODE_TRUST_CERTIFICATE.KIND);
    if (kind == null) {
      return;
    }
    validateOrdinal(
        persistedBundle.version(), certificate.get(TRANSCODE_TRUST_CERTIFICATE.ORDINAL));
    var certificateDer = certificate.get(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER);
    if (!MessageDigest.isEqual(
        sha256(certificateDer), certificate.get(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256))) {
      throw new InstallationTrustException(
          "Stored public trust certificate digest does not match exact DER");
    }
    certificatesByRole.add(kind, parse(certificateDer));
  }

  private void validateOrdinal(long bundleVersion, short ordinal) {
    if (ordinal < 0) {
      throw new InstallationTrustException("Public trust certificate has a negative role ordinal");
    }
    if (bundleVersion == INITIAL_BUNDLE_VERSION && ordinal != 0) {
      throw new InstallationTrustException(
          "Initial public trust certificate has an invalid role and ordinal");
    }
  }

  private void validateCertificateRoles(
      PersistedPublicTrustBundle persistedBundle, CertificateRoles certificatesByRole) {
    if (persistedBundle.version() == INITIAL_BUNDLE_VERSION
        && !certificatesByRole.hasExactInitialRoles()) {
      throw new InstallationTrustException(
          "Initial public trust bundle must contain the exact certificate roles");
    }
    if (certificatesByRole.isMissingRequiredRole()) {
      throw new InstallationTrustException(
          "Public trust bundle must contain every required certificate role");
    }
  }

  private PublicTrustBundle toPublicTrustBundle(
      PersistedPublicTrustBundle persistedBundle, CertificateRoles certificatesByRole) {
    return PublicTrustBundle.builder()
        .installationId(persistedBundle.installationId())
        .version(persistedBundle.version())
        .createdAt(persistedBundle.createdAt().toInstant())
        .trustAnchors(certificatesByRole.trustAnchors())
        .issuers(certificatesByRole.issuers())
        .revocationSigners(certificatesByRole.revocationSigners())
        .build();
  }

  @Override
  public boolean publishInitial(
      CertificateSigningLease lease, InitialTrustPublication publication) {
    if (lease.operation() != CertificateAuthorityOperation.BOOTSTRAP) {
      return false;
    }
    return dsl.transactionResult(
        configuration -> publishInitial(DSL.using(configuration), lease, publication));
  }

  private boolean publishInitial(
      DSLContext transaction, CertificateSigningLease lease, InitialTrustPublication publication) {
    var currentLease =
        transaction
            .select(TRANSCODE_CA_SIGNING_LEASE.SINGLETON)
            .from(TRANSCODE_CA_SIGNING_LEASE)
            .where(CertificateSigningLeaseGuard.identity(lease))
            .forUpdate()
            .fetchOptional();
    if (currentLease.isEmpty()) {
      return false;
    }

    var installation =
        transaction
            .select(
                TRANSCODE_INSTALLATION.INSTALLATION_ID,
                TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256)
            .from(TRANSCODE_INSTALLATION)
            .where(TRANSCODE_INSTALLATION.SINGLETON.isTrue())
            .forUpdate()
            .fetchSingle();
    if (!installation.value1().equals(publication.installationId())
        || installation.value2() != null) {
      return false;
    }

    var activeBundle =
        transaction
            .select(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION)
            .from(TRANSCODE_ACTIVE_TRUST_BUNDLE)
            .where(TRANSCODE_ACTIVE_TRUST_BUNDLE.SINGLETON.isTrue())
            .forUpdate()
            .fetchSingle(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION);
    if (activeBundle != null) {
      return false;
    }
    if (!CertificateSigningLeaseGuard.isCurrent(transaction, lease)) {
      return false;
    }

    transaction
        .insertInto(TRANSCODE_PUBLIC_TRUST_BUNDLE)
        .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.INSTALLATION_ID, publication.installationId())
        .set(TRANSCODE_PUBLIC_TRUST_BUNDLE.VERSION, INITIAL_BUNDLE_VERSION)
        .execute();
    insertCertificate(transaction, publication, TranscodeTrustCertificateKind.TRUST_ANCHOR);
    insertCertificate(transaction, publication, TranscodeTrustCertificateKind.ISSUER);
    insertCertificate(transaction, publication, TranscodeTrustCertificateKind.REVOCATION_SIGNER);

    var initialized =
        transaction
            .update(TRANSCODE_INSTALLATION)
            .set(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256, publication.bootstrapRootSha256())
            .set(
                TRANSCODE_INSTALLATION.INITIALIZED_AT,
                CertificateSigningLeaseGuard.statementTimestamp())
            .where(TRANSCODE_INSTALLATION.SINGLETON.isTrue())
            .and(TRANSCODE_INSTALLATION.BOOTSTRAP_ROOT_SHA256.isNull())
            .execute();
    var activated =
        transaction
            .update(TRANSCODE_ACTIVE_TRUST_BUNDLE)
            .set(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION, INITIAL_BUNDLE_VERSION)
            .set(
                TRANSCODE_ACTIVE_TRUST_BUNDLE.ACTIVATED_AT,
                CertificateSigningLeaseGuard.statementTimestamp())
            .where(TRANSCODE_ACTIVE_TRUST_BUNDLE.SINGLETON.isTrue())
            .and(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION.isNull())
            .execute();
    if (initialized != 1 || activated != 1) {
      throw new InstallationTrustException(
          "Installation trust publication did not commit its final state");
    }
    if (!CertificateSigningLeaseGuard.isCurrent(transaction, lease)) {
      throw new InstallationTrustException(
          "Installation trust publication lease expired before final commit");
    }
    return true;
  }

  private void insertCertificate(
      DSLContext transaction,
      InitialTrustPublication publication,
      TranscodeTrustCertificateKind kind) {
    var certificateDer =
        switch (kind) {
          case TRUST_ANCHOR -> publication.rootCertificateDer();
          case ISSUER -> publication.issuerCertificateDer();
          case REVOCATION_SIGNER -> publication.revocationSignerCertificateDer();
        };
    transaction
        .insertInto(TRANSCODE_TRUST_CERTIFICATE)
        .set(TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID, publication.installationId())
        .set(TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION, INITIAL_BUNDLE_VERSION)
        .set(TRANSCODE_TRUST_CERTIFICATE.KIND, kind)
        .set(TRANSCODE_TRUST_CERTIFICATE.ORDINAL, (short) 0)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER, certificateDer)
        .set(TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256, sha256(certificateDer))
        .execute();
  }

  private X509Certificate parse(byte[] der) {
    try {
      var input = new ByteArrayInputStream(der);
      var certificate =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      if (input.available() != 0 || !MessageDigest.isEqual(certificate.getEncoded(), der)) {
        throw new InstallationTrustException(
            "Stored public trust certificate is not canonical DER");
      }
      return certificate;
    } catch (java.security.cert.CertificateException e) {
      throw new InstallationTrustException(
          "Stored public trust certificate is not valid canonical DER", e);
    }
  }

  private byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private byte[] sha256(X509Certificate certificate) {
    try {
      return sha256(certificate.getEncoded());
    } catch (java.security.cert.CertificateEncodingException e) {
      throw new InstallationTrustException("Stored public trust certificate cannot be encoded", e);
    }
  }

  @Builder
  private record PersistedTrustState(
      UUID installationId,
      OffsetDateTime initializedAt,
      Long bundleVersion,
      OffsetDateTime activatedAt,
      OffsetDateTime createdAt) {

    private boolean isEmpty(byte[] rootDigest) {
      return rootDigest == null
          && initializedAt == null
          && bundleVersion == null
          && activatedAt == null
          && createdAt == null;
    }

    private void requireComplete(byte[] rootDigest) {
      if (rootDigest == null
          || initializedAt == null
          || bundleVersion == null
          || activatedAt == null
          || createdAt == null) {
        throw new InstallationTrustException(
            "Installation trust state is only partially initialized");
      }
    }

    private PersistedPublicTrustBundle bundle() {
      return new PersistedPublicTrustBundle(installationId, bundleVersion, createdAt);
    }
  }

  private record PersistedPublicTrustBundle(
      UUID installationId, long version, OffsetDateTime createdAt) {}

  private record CertificateRoles(
      List<X509Certificate> trustAnchors,
      List<X509Certificate> issuers,
      List<X509Certificate> revocationSigners) {

    private static CertificateRoles empty() {
      return new CertificateRoles(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    private void add(TranscodeTrustCertificateKind kind, X509Certificate certificate) {
      switch (kind) {
        case TRUST_ANCHOR -> trustAnchors.add(certificate);
        case ISSUER -> issuers.add(certificate);
        case REVOCATION_SIGNER -> revocationSigners.add(certificate);
      }
    }

    private boolean hasExactInitialRoles() {
      return trustAnchors.size() == 1 && issuers.size() == 1 && revocationSigners.size() == 1;
    }

    private boolean isMissingRequiredRole() {
      return trustAnchors.isEmpty() || issuers.isEmpty() || revocationSigners.isEmpty();
    }
  }
}
