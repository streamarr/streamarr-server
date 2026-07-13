package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerCertificateIssuance.TRANSCODE_WORKER_CERTIFICATE_ISSUANCE;

import com.streamarr.server.jooq.generated.tables.records.TranscodeWorkerCertificateIssuanceRecord;
import com.streamarr.server.services.streaming.trust.CertificateAuthorityOperation;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRejection;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimRequest;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceClaimResult;
import com.streamarr.server.services.streaming.trust.CertificateIssuanceParameters;
import com.streamarr.server.services.streaming.trust.CertificateSerialNumber;
import com.streamarr.server.services.streaming.trust.CertificateSigningLease;
import com.streamarr.server.services.streaming.trust.CertificateValidity;
import com.streamarr.server.services.streaming.trust.EnrollmentCertificateRequest;
import com.streamarr.server.services.streaming.trust.InstallationTrustException;
import com.streamarr.server.services.streaming.trust.PublicTrustBundleRef;
import com.streamarr.server.services.streaming.trust.Sha256Digest;
import com.streamarr.server.services.streaming.trust.SubjectPublicKeyInfo;
import com.streamarr.server.services.streaming.trust.WorkerCertificateProfile;
import java.time.ZoneOffset;
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

  @Override
  public CertificateIssuanceClaimResult claim(
      CertificateIssuanceClaimRequest request, CertificateSigningLease lease) {
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
    if (!grantRecord.value3().equals(request.workerId()) || grantRecord.value6() != null) {
      return rejected(CertificateIssuanceClaimRejection.ENROLLMENT_GRANT_INVALID);
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
    if (!isExactRequest(existing.orElseThrow(), context.request(), context.grantId())) {
      return rejected(CertificateIssuanceClaimRejection.REQUEST_CONFLICT);
    }
    var retained = reclaim(transaction, existing.orElseThrow(), context.lease());
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
    var parameters =
        CertificateIssuanceParameters.builder()
            .issuerCertificateSha256(new Sha256Digest(issuanceRecord.getIssuerCertificateSha256()))
            .serialNumber(
                CertificateSerialNumber.fromUnsignedBytes(issuanceRecord.getSerialNumber()))
            .profile(
                WorkerCertificateProfile.fromVersion(issuanceRecord.getCertificateProfileVersion()))
            .validity(
                new CertificateValidity(
                    issuanceRecord.getNotBefore().toInstant(),
                    issuanceRecord.getNotAfter().toInstant()))
            .build();
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

  private CertificateIssuanceClaimResult.Rejected rejected(
      CertificateIssuanceClaimRejection reason) {
    return new CertificateIssuanceClaimResult.Rejected(reason);
  }

  private static final class SigningLeaseLostException extends RuntimeException {

    private SigningLeaseLostException() {
      super("Certificate signing lease expired before final commit");
    }
  }

  @Builder
  private record RetainedClaimContext(
      EnrollmentCertificateRequest request,
      UUID grantId,
      CertificateSigningLease lease,
      CertificateIssuanceClaimRejection missingClaimRejection) {}
}
