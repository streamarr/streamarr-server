package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodeTrustCertificate.TRANSCODE_TRUST_CERTIFICATE;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerCertificateIssuance.TRANSCODE_WORKER_CERTIFICATE_ISSUANCE;

import com.streamarr.server.jooq.generated.tables.records.TranscodeWorkerCertificateIssuanceRecord;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRejection;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRequest;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimResult;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletion;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletionRejection;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceCompletionResult;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceParameters;
import com.streamarr.server.services.streaming.trust.CertificateSerialNumber;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.CertificateValidity;
import com.streamarr.server.services.streaming.trust.EncodedWorkerCertificate;
import com.streamarr.server.services.streaming.trust.EnrollmentCertificateRequest;
import com.streamarr.server.services.streaming.trust.InstallationTrustException;
import com.streamarr.server.services.streaming.trust.IssuedWorkerCertificate;
import com.streamarr.server.services.streaming.trust.PublicTrustBundleRef;
import com.streamarr.server.services.streaming.trust.Sha256Digest;
import com.streamarr.server.services.streaming.trust.SubjectPublicKeyInfo;
import com.streamarr.server.services.streaming.trust.WorkerCertificateProfile;
import com.streamarr.server.services.streaming.trust.WorkerCertificateValidator;
import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
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
public class WorkerCertificateIssuanceRepositoryImpl
    implements WorkerCertificateIssuanceRepository {

  private final DSLContext dsl;
  private final InstallationTrustRepository trustRepository;
  private final WorkerCertificateValidator certificateValidator;

  @Override
  public CertificateIssuanceClaimResult claim(
      CertificateIssuanceClaimRequest request, CertificateSigningLease lease) {
    var completed = findCompleted(request.request());
    if (completed.isPresent()) {
      return new CertificateIssuanceClaimResult.Completed(completed.orElseThrow());
    }
    if (lease.operation() != CertificateAuthorityOperation.ISSUANCE) {
      return rejected(CertificateIssuanceClaimRejection.SIGNING_LEASE_UNAVAILABLE);
    }
    try {
      return dsl.transactionResult(
          configuration -> claim(DSL.using(configuration), request, lease));
    } catch (SigningLeaseLostException _) {
      return rejected(CertificateIssuanceClaimRejection.SIGNING_LEASE_UNAVAILABLE);
    }
  }

  @Override
  public CertificateIssuanceCompletionResult complete(
      CertificateIssuanceCompletion completion, CertificateSigningLease lease) {
    if (lease.operation() != CertificateAuthorityOperation.ISSUANCE) {
      return completionRejected(CertificateIssuanceCompletionRejection.SIGNING_LEASE_UNAVAILABLE);
    }
    try {
      var persisted =
          dsl.transactionResult(
              configuration -> complete(DSL.using(configuration), completion, lease));
      return new CertificateIssuanceCompletionResult.Stored(toIssuedCertificate(persisted));
    } catch (SigningLeaseLostException _) {
      return completionRejected(CertificateIssuanceCompletionRejection.SIGNING_LEASE_UNAVAILABLE);
    } catch (CompletionRejectedException rejected) {
      return completionRejected(rejected.reason());
    }
  }

  @Override
  public Optional<IssuedWorkerCertificate> findCompleted(EnrollmentCertificateRequest request) {
    Objects.requireNonNull(request);
    var issuance =
        dsl.selectFrom(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(request.requestId()))
            .and(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.WORKER_ID.eq(request.workerId()))
            .and(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SUBJECT_PUBLIC_KEY_INFO_DER.eq(
                    request.subjectPublicKeyInfo().der()))
            .and(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SUBJECT_PUBLIC_KEY_SHA256.eq(
                    request.subjectPublicKeyInfo().sha256().bytes()))
            .and(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER.isNotNull())
            .andExists(
                dsl.selectOne()
                    .from(TRANSCODE_ENROLLMENT_GRANT)
                    .where(
                        TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(
                            TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.GRANT_ID))
                    .and(TRANSCODE_ENROLLMENT_GRANT.TOKEN_SHA256.eq(request.tokenSha256().bytes())))
            .fetchOptional();
    return issuance.map(this::persistedCertificate).map(this::toIssuedCertificate);
  }

  private PersistedCertificate complete(
      DSLContext transaction,
      CertificateIssuanceCompletion completion,
      CertificateSigningLease lease) {
    if (!CertificateSigningLeaseGuard.lockCurrent(transaction, lease)) {
      throw new SigningLeaseLostException();
    }
    var grantId =
        transaction
            .select(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.GRANT_ID)
            .from(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(completion.requestId()))
            .fetchOptional(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.GRANT_ID)
            .orElseThrow(CompletionRejectedException::claimUnavailable);
    var grant =
        transaction
            .select(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID, TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT)
            .from(TRANSCODE_ENROLLMENT_GRANT)
            .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(grantId))
            .forUpdate()
            .fetchOptional();
    var issuance =
        transaction
            .selectFrom(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(completion.requestId()))
            .forUpdate()
            .fetchOptional()
            .orElseThrow(CompletionRejectedException::claimUnavailable);
    var databaseTime = CertificateSigningLeaseGuard.databaseTime(transaction);
    var validationContext =
        WorkerCertificateValidator.ValidationContext.builder()
            .installationId(issuance.getInstallationId())
            .workerId(issuance.getWorkerId())
            .subjectPublicKeyInfo(
                SubjectPublicKeyInfo.fromDer(issuance.getSubjectPublicKeyInfoDer()))
            .parameters(certificateParameters(issuance))
            .issuer(pinnedIssuer(transaction, issuance))
            .build();
    if (!certificateValidator.matches(completion.certificate(), validationContext)) {
      throw CompletionRejectedException.certificateMismatch();
    }
    if (issuance.getCertificateDer() != null) {
      return persistedCertificate(issuance);
    }
    if (issuance.getSigningFencingEpoch() != completion.signingFencingEpoch()
        || completion.signingFencingEpoch() != lease.fencingEpoch()
        || grant.isEmpty()
        || grant.orElseThrow().value2() != null) {
      throw CompletionRejectedException.claimUnavailable();
    }
    var consumed =
        transaction
            .update(TRANSCODE_ENROLLMENT_GRANT)
            .set(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT, databaseTime)
            .where(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID.eq(grantId))
            .and(TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT.isNull())
            .execute();
    if (consumed != 1) {
      throw CompletionRejectedException.claimUnavailable();
    }
    var stored =
        transaction
            .update(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER,
                completion.certificate().der())
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_SHA256,
                completion.certificate().sha256().bytes())
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.COMPLETED_AT, databaseTime)
            .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(completion.requestId()))
            .and(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SIGNING_FENCING_EPOCH.eq(
                    completion.signingFencingEpoch()))
            .and(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER.isNull())
            .returning()
            .fetchOptional()
            .orElseThrow(CompletionRejectedException::claimUnavailable);
    if (!CertificateSigningLeaseGuard.isCurrent(transaction, lease)) {
      throw new SigningLeaseLostException();
    }
    return persistedCertificate(stored);
  }

  private X509Certificate pinnedIssuer(
      DSLContext transaction, TranscodeWorkerCertificateIssuanceRecord issuance) {
    var stored =
        transaction
            .select(
                TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_DER,
                TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256)
            .from(TRANSCODE_TRUST_CERTIFICATE)
            .where(TRANSCODE_TRUST_CERTIFICATE.INSTALLATION_ID.eq(issuance.getInstallationId()))
            .and(TRANSCODE_TRUST_CERTIFICATE.BUNDLE_VERSION.eq(issuance.getTrustBundleVersion()))
            .and(TRANSCODE_TRUST_CERTIFICATE.KIND.eq(issuance.getIssuerCertificateKind()))
            .and(
                TRANSCODE_TRUST_CERTIFICATE.CERTIFICATE_SHA256.eq(
                    issuance.getIssuerCertificateSha256()))
            .fetchOptional()
            .orElseThrow(
                () ->
                    new InstallationTrustException(
                        "Worker certificate claim references a missing issuing certificate"));
    var der = stored.value1();
    try {
      var digest = new Sha256Digest(MessageDigest.getInstance("SHA-256").digest(der));
      if (!digest.equals(new Sha256Digest(stored.value2()))) {
        throw new InstallationTrustException(
            "Stored issuing certificate digest does not match exact DER");
      }
      var input = new ByteArrayInputStream(der);
      var issuer =
          (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
      if (input.available() != 0 || !MessageDigest.isEqual(der, issuer.getEncoded())) {
        throw new InstallationTrustException("Stored issuing certificate is not canonical DER");
      }
      return issuer;
    } catch (GeneralSecurityException e) {
      throw new InstallationTrustException("Stored issuing certificate is invalid", e);
    }
  }

  private IssuedWorkerCertificate toIssuedCertificate(PersistedCertificate persisted) {
    var trustBundle =
        trustRepository
            .findBundle(persisted.installationId(), persisted.trustBundleVersion())
            .orElseThrow(
                () ->
                    new InstallationTrustException(
                        "Stored worker certificate references a missing public trust bundle"));
    return IssuedWorkerCertificate.builder()
        .requestId(persisted.requestId())
        .workerId(persisted.workerId())
        .certificate(persisted.certificate())
        .trustBundle(trustBundle)
        .build();
  }

  private PersistedCertificate persistedCertificate(
      TranscodeWorkerCertificateIssuanceRecord issuanceRecord) {
    var subjectPublicKey =
        SubjectPublicKeyInfo.fromDer(issuanceRecord.getSubjectPublicKeyInfoDer());
    if (!subjectPublicKey
        .sha256()
        .equals(new Sha256Digest(issuanceRecord.getSubjectPublicKeySha256()))) {
      throw new InstallationTrustException(
          "Stored worker subject public key digest does not match exact DER");
    }
    EncodedWorkerCertificate certificate;
    try {
      certificate = EncodedWorkerCertificate.fromDer(issuanceRecord.getCertificateDer());
    } catch (IllegalArgumentException e) {
      throw new InstallationTrustException("Stored worker certificate is invalid", e);
    }
    if (!certificate.sha256().equals(new Sha256Digest(issuanceRecord.getCertificateSha256()))) {
      throw new InstallationTrustException(
          "Stored worker certificate digest does not match exact DER");
    }
    return new PersistedCertificate(
        issuanceRecord.getRequestId(),
        issuanceRecord.getInstallationId(),
        issuanceRecord.getWorkerId(),
        issuanceRecord.getTrustBundleVersion(),
        certificate);
  }

  private CertificateIssuanceClaimResult claim(
      DSLContext transaction,
      CertificateIssuanceClaimRequest claimRequest,
      CertificateSigningLease lease) {
    if (!CertificateSigningLeaseGuard.lockCurrent(transaction, lease)) {
      return rejected(CertificateIssuanceClaimRejection.SIGNING_LEASE_UNAVAILABLE);
    }
    var request = claimRequest.request();
    var grant =
        transaction
            .select(
                TRANSCODE_ENROLLMENT_GRANT.GRANT_ID,
                TRANSCODE_ENROLLMENT_GRANT.INSTALLATION_ID,
                TRANSCODE_ENROLLMENT_GRANT.WORKER_ID,
                TRANSCODE_ENROLLMENT_GRANT.TRUST_BUNDLE_VERSION,
                TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT,
                TRANSCODE_ENROLLMENT_GRANT.CONSUMED_AT)
            .from(TRANSCODE_ENROLLMENT_GRANT)
            .where(TRANSCODE_ENROLLMENT_GRANT.TOKEN_SHA256.eq(request.tokenSha256().bytes()))
            .forUpdate()
            .fetchOptional();
    var databaseTime = CertificateSigningLeaseGuard.databaseTime(transaction);
    if (grant.isEmpty()) {
      return rejected(CertificateIssuanceClaimRejection.ENROLLMENT_GRANT_INVALID);
    }

    var grantRecord = grant.orElseThrow();
    if (!grantRecord.value3().equals(request.workerId())) {
      return rejected(CertificateIssuanceClaimRejection.ENROLLMENT_GRANT_INVALID);
    }
    if (grantRecord.value6() != null) {
      return retainedClaim(
          transaction,
          RetainedClaimContext.builder()
              .request(request)
              .grantId(grantRecord.value1())
              .lease(lease)
              .missingClaimRejection(CertificateIssuanceClaimRejection.ENROLLMENT_GRANT_INVALID)
              .build());
    }
    if (!grantRecord.value5().isAfter(databaseTime)) {
      return retainedClaim(
          transaction,
          RetainedClaimContext.builder()
              .request(request)
              .grantId(grantRecord.value1())
              .lease(lease)
              .missingClaimRejection(CertificateIssuanceClaimRejection.ENROLLMENT_GRANT_INVALID)
              .build());
    }
    var parameters = claimRequest.proposedParameters();
    var inserted =
        transaction
            .insertInto(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID, request.requestId())
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.GRANT_ID, grantRecord.value1())
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.INSTALLATION_ID, grantRecord.value2())
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.WORKER_ID, grantRecord.value3())
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.TRUST_BUNDLE_VERSION, grantRecord.value4())
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SUBJECT_PUBLIC_KEY_INFO_DER,
                request.subjectPublicKeyInfo().der())
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SUBJECT_PUBLIC_KEY_SHA256,
                request.subjectPublicKeyInfo().sha256().bytes())
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.ISSUER_CERTIFICATE_SHA256,
                parameters.issuerCertificateSha256().bytes())
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SERIAL_NUMBER,
                parameters.serialNumber().unsignedBytes())
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_PROFILE_VERSION,
                parameters.profile().version())
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.NOT_BEFORE,
                parameters.validity().notBefore().atOffset(ZoneOffset.UTC))
            .set(
                TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.NOT_AFTER,
                parameters.validity().notAfter().atOffset(ZoneOffset.UTC))
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CREATED_AT, databaseTime)
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CLAIMED_AT, databaseTime)
            .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SIGNING_FENCING_EPOCH, lease.fencingEpoch())
            .onConflictDoNothing()
            .returning()
            .fetchOptional();
    if (inserted.isEmpty()) {
      return retainedClaim(
          transaction,
          RetainedClaimContext.builder()
              .request(request)
              .grantId(grantRecord.value1())
              .lease(lease)
              .missingClaimRejection(CertificateIssuanceClaimRejection.REQUEST_CONFLICT)
              .build());
    }
    if (!CertificateSigningLeaseGuard.isCurrent(transaction, lease)) {
      throw new SigningLeaseLostException();
    }
    return toReadyToSign(inserted.orElseThrow());
  }

  private CertificateIssuanceClaimResult retainedClaim(
      DSLContext transaction, RetainedClaimContext context) {
    var existing =
        transaction
            .selectFrom(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.GRANT_ID.eq(context.grantId()))
            .forUpdate()
            .fetchOptional();
    if (existing.isEmpty()) {
      return missingClaim(transaction, context);
    }
    var retained = existing.orElseThrow();
    if (!isExactRequest(retained, context.request(), context.grantId())) {
      return rejected(CertificateIssuanceClaimRejection.REQUEST_CONFLICT);
    }
    if (retained.getCertificateDer() != null) {
      return new CertificateIssuanceClaimResult.Completed(
          toIssuedCertificate(persistedCertificate(retained)));
    }
    retained = reclaim(transaction, retained, context.lease());
    if (!CertificateSigningLeaseGuard.isCurrent(transaction, context.lease())) {
      throw new SigningLeaseLostException();
    }
    return toReadyToSign(retained);
  }

  private CertificateIssuanceClaimResult missingClaim(
      DSLContext transaction, RetainedClaimContext context) {
    if (context.missingClaimRejection() != CertificateIssuanceClaimRejection.REQUEST_CONFLICT) {
      return rejected(context.missingClaimRejection());
    }
    if (requestIdExists(transaction, context.request().requestId())) {
      return rejected(CertificateIssuanceClaimRejection.REQUEST_CONFLICT);
    }
    return new CertificateIssuanceClaimResult.RetryWithNewParameters();
  }

  private boolean requestIdExists(DSLContext transaction, UUID requestId) {
    return transaction.fetchExists(
        transaction
            .selectOne()
            .from(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
            .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(requestId)));
  }

  private boolean isExactRequest(
      TranscodeWorkerCertificateIssuanceRecord existing,
      EnrollmentCertificateRequest request,
      UUID grantId) {
    return existing.getGrantId().equals(grantId)
        && existing.getRequestId().equals(request.requestId())
        && existing.getWorkerId().equals(request.workerId())
        && SubjectPublicKeyInfo.fromDer(existing.getSubjectPublicKeyInfoDer())
            .equals(request.subjectPublicKeyInfo());
  }

  private TranscodeWorkerCertificateIssuanceRecord reclaim(
      DSLContext transaction,
      TranscodeWorkerCertificateIssuanceRecord existing,
      CertificateSigningLease lease) {
    if (existing.getSigningFencingEpoch() == lease.fencingEpoch()) {
      return existing;
    }
    return transaction
        .update(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE)
        .set(
            TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CLAIMED_AT,
            CertificateSigningLeaseGuard.statementTimestamp())
        .set(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.SIGNING_FENCING_EPOCH, lease.fencingEpoch())
        .where(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.REQUEST_ID.eq(existing.getRequestId()))
        .and(TRANSCODE_WORKER_CERTIFICATE_ISSUANCE.CERTIFICATE_DER.isNull())
        .returning()
        .fetchSingle();
  }

  private CertificateIssuanceClaimResult.ReadyToSign toReadyToSign(
      TranscodeWorkerCertificateIssuanceRecord issuanceRecord) {
    var publicKey = SubjectPublicKeyInfo.fromDer(issuanceRecord.getSubjectPublicKeyInfoDer());
    if (!publicKey.sha256().equals(new Sha256Digest(issuanceRecord.getSubjectPublicKeySha256()))) {
      throw new InstallationTrustException(
          "Stored worker subject public key digest does not match exact DER");
    }
    var parameters = certificateParameters(issuanceRecord);
    return CertificateIssuanceClaimResult.ReadyToSign.builder()
        .requestId(issuanceRecord.getRequestId())
        .workerId(issuanceRecord.getWorkerId())
        .trustBundle(
            new PublicTrustBundleRef(
                issuanceRecord.getInstallationId(), issuanceRecord.getTrustBundleVersion()))
        .subjectPublicKeyInfo(publicKey)
        .parameters(parameters)
        .signingFencingEpoch(issuanceRecord.getSigningFencingEpoch())
        .build();
  }

  private CertificateIssuanceParameters certificateParameters(
      TranscodeWorkerCertificateIssuanceRecord issuanceRecord) {
    return CertificateIssuanceParameters.builder()
        .issuerCertificateSha256(new Sha256Digest(issuanceRecord.getIssuerCertificateSha256()))
        .serialNumber(CertificateSerialNumber.fromUnsignedBytes(issuanceRecord.getSerialNumber()))
        .profile(
            WorkerCertificateProfile.fromVersion(issuanceRecord.getCertificateProfileVersion()))
        .validity(
            new CertificateValidity(
                issuanceRecord.getNotBefore().toInstant(),
                issuanceRecord.getNotAfter().toInstant()))
        .build();
  }

  private CertificateIssuanceClaimResult.Rejected rejected(
      CertificateIssuanceClaimRejection reason) {
    return new CertificateIssuanceClaimResult.Rejected(reason);
  }

  private CertificateIssuanceCompletionResult.Rejected completionRejected(
      CertificateIssuanceCompletionRejection reason) {
    return new CertificateIssuanceCompletionResult.Rejected(reason);
  }

  private static final class SigningLeaseLostException extends RuntimeException {

    private SigningLeaseLostException() {
      super("Certificate signing lease expired before final commit");
    }
  }

  private static final class CompletionRejectedException extends RuntimeException {

    private final CertificateIssuanceCompletionRejection reason;

    private CompletionRejectedException(CertificateIssuanceCompletionRejection reason) {
      super("Certificate issuance completion rejected: " + reason);
      this.reason = reason;
    }

    static CompletionRejectedException claimUnavailable() {
      return new CompletionRejectedException(
          CertificateIssuanceCompletionRejection.CLAIM_UNAVAILABLE);
    }

    static CompletionRejectedException certificateMismatch() {
      return new CompletionRejectedException(
          CertificateIssuanceCompletionRejection.CERTIFICATE_MISMATCH);
    }

    CertificateIssuanceCompletionRejection reason() {
      return reason;
    }
  }

  @Builder
  private record RetainedClaimContext(
      EnrollmentCertificateRequest request,
      UUID grantId,
      CertificateSigningLease lease,
      CertificateIssuanceClaimRejection missingClaimRejection) {}

  private record PersistedCertificate(
      UUID requestId,
      UUID installationId,
      UUID workerId,
      long trustBundleVersion,
      EncodedWorkerCertificate certificate) {}
}
