package com.streamarr.server.repositories.streaming.trust;

import static com.streamarr.server.jooq.generated.tables.TranscodeActiveTrustBundle.TRANSCODE_ACTIVE_TRUST_BUNDLE;
import static com.streamarr.server.jooq.generated.tables.TranscodeEnrollmentGrant.TRANSCODE_ENROLLMENT_GRANT;
import static com.streamarr.server.jooq.generated.tables.TranscodeWorkerIdentity.TRANSCODE_WORKER_IDENTITY;

import com.streamarr.server.services.streaming.trust.EnrollmentGrant;
import com.streamarr.server.services.streaming.trust.EnrollmentGrantRequest;
import com.streamarr.server.services.streaming.trust.GrantCreationConflict;
import com.streamarr.server.services.streaming.trust.GrantCreationResult;
import com.streamarr.server.services.streaming.trust.InstallationTrustException;
import com.streamarr.server.services.streaming.trust.PublicTrustBundleRef;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class WorkerEnrollmentRepositoryImpl implements WorkerEnrollmentRepository {

  private final DSLContext dsl;
  private final InstallationTrustRepository trustRepository;

  @Override
  @Transactional
  public GrantCreationResult createGrant(EnrollmentGrantRequest request) {
    var activeBundle =
        dsl.select(
                TRANSCODE_ACTIVE_TRUST_BUNDLE.INSTALLATION_ID,
                TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION)
            .from(TRANSCODE_ACTIVE_TRUST_BUNDLE)
            .where(TRANSCODE_ACTIVE_TRUST_BUNDLE.SINGLETON.isTrue())
            .and(TRANSCODE_ACTIVE_TRUST_BUNDLE.BUNDLE_VERSION.isNotNull())
            .forShare()
            .fetchOptional()
            .orElseThrow(() -> new InstallationTrustException("Installation trust is unavailable"));
    var databaseTime = dsl.select(statementTimestamp()).fetchSingle().value1();
    var grantId = UUID.randomUUID();

    var workerInserted =
        dsl.insertInto(TRANSCODE_WORKER_IDENTITY)
            .set(TRANSCODE_WORKER_IDENTITY.INSTALLATION_ID, activeBundle.value1())
            .set(TRANSCODE_WORKER_IDENTITY.WORKER_ID, request.workerId())
            .set(TRANSCODE_WORKER_IDENTITY.CREATED_AT, databaseTime)
            .onConflictDoNothing()
            .execute();
    var inserted =
        dsl.insertInto(TRANSCODE_ENROLLMENT_GRANT)
            .set(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID, grantId)
            .set(TRANSCODE_ENROLLMENT_GRANT.INSTALLATION_ID, activeBundle.value1())
            .set(TRANSCODE_ENROLLMENT_GRANT.WORKER_ID, request.workerId())
            .set(TRANSCODE_ENROLLMENT_GRANT.TRUST_BUNDLE_VERSION, activeBundle.value2())
            .set(TRANSCODE_ENROLLMENT_GRANT.TOKEN_SHA256, request.tokenSha256().bytes())
            .set(TRANSCODE_ENROLLMENT_GRANT.CREATED_AT, databaseTime)
            .set(TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT, databaseTime.plus(request.lifetime()))
            .onConflict(TRANSCODE_ENROLLMENT_GRANT.TOKEN_SHA256)
            .doNothing()
            .returning(
                TRANSCODE_ENROLLMENT_GRANT.GRANT_ID,
                TRANSCODE_ENROLLMENT_GRANT.INSTALLATION_ID,
                TRANSCODE_ENROLLMENT_GRANT.WORKER_ID,
                TRANSCODE_ENROLLMENT_GRANT.TRUST_BUNDLE_VERSION,
                TRANSCODE_ENROLLMENT_GRANT.CREATED_AT,
                TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT)
            .fetchOptional();
    if (inserted.isEmpty()) {
      return retainedGrant(request, activeBundle.value1(), workerInserted == 1);
    }

    var grant = toGrant(inserted.orElseThrow());
    var trustBundle = findBundle(grant.trustBundle());
    return new GrantCreationResult.Created(grant, trustBundle);
  }

  private GrantCreationResult retainedGrant(
      EnrollmentGrantRequest request, UUID installationId, boolean workerWasInserted) {
    var existing =
        dsl.select(
                TRANSCODE_ENROLLMENT_GRANT.GRANT_ID,
                TRANSCODE_ENROLLMENT_GRANT.INSTALLATION_ID,
                TRANSCODE_ENROLLMENT_GRANT.WORKER_ID,
                TRANSCODE_ENROLLMENT_GRANT.TRUST_BUNDLE_VERSION,
                TRANSCODE_ENROLLMENT_GRANT.CREATED_AT,
                TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT)
            .from(TRANSCODE_ENROLLMENT_GRANT)
            .where(TRANSCODE_ENROLLMENT_GRANT.TOKEN_SHA256.eq(request.tokenSha256().bytes()))
            .fetchSingle();
    if (!existing.value3().equals(request.workerId())) {
      removeUnreferencedWorker(installationId, request.workerId(), workerWasInserted);
      return new GrantCreationResult.Conflict(GrantCreationConflict.TOKEN_DIGEST_IN_USE);
    }
    var grant = toGrant(existing);
    return new GrantCreationResult.Retained(grant, findBundle(grant.trustBundle()));
  }

  private EnrollmentGrant toGrant(org.jooq.Record grantRecord) {
    return EnrollmentGrant.builder()
        .grantId(grantRecord.get(TRANSCODE_ENROLLMENT_GRANT.GRANT_ID))
        .workerId(grantRecord.get(TRANSCODE_ENROLLMENT_GRANT.WORKER_ID))
        .trustBundle(
            new PublicTrustBundleRef(
                grantRecord.get(TRANSCODE_ENROLLMENT_GRANT.INSTALLATION_ID),
                grantRecord.get(TRANSCODE_ENROLLMENT_GRANT.TRUST_BUNDLE_VERSION)))
        .createdAt(grantRecord.get(TRANSCODE_ENROLLMENT_GRANT.CREATED_AT).toInstant())
        .expiresAt(grantRecord.get(TRANSCODE_ENROLLMENT_GRANT.EXPIRES_AT).toInstant())
        .build();
  }

  private void removeUnreferencedWorker(
      UUID installationId, UUID workerId, boolean workerWasInserted) {
    if (!workerWasInserted) {
      return;
    }
    dsl.deleteFrom(TRANSCODE_WORKER_IDENTITY)
        .where(TRANSCODE_WORKER_IDENTITY.INSTALLATION_ID.eq(installationId))
        .and(TRANSCODE_WORKER_IDENTITY.WORKER_ID.eq(workerId))
        .andNotExists(
            dsl.selectOne()
                .from(TRANSCODE_ENROLLMENT_GRANT)
                .where(TRANSCODE_ENROLLMENT_GRANT.INSTALLATION_ID.eq(installationId))
                .and(TRANSCODE_ENROLLMENT_GRANT.WORKER_ID.eq(workerId)))
        .execute();
  }

  private com.streamarr.server.services.streaming.trust.PublicTrustBundle findBundle(
      PublicTrustBundleRef bundleRef) {
    return trustRepository
        .findBundle(bundleRef.installationId(), bundleRef.version())
        .orElseThrow(() -> new InstallationTrustException("Public trust bundle is missing"));
  }

  private Field<OffsetDateTime> statementTimestamp() {
    return DSL.function(DSL.name("statement_timestamp"), SQLDataType.TIMESTAMPWITHTIMEZONE);
  }
}
