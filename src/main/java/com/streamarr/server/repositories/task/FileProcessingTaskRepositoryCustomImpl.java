package com.streamarr.server.repositories.task;

import static com.streamarr.server.jooq.generated.Tables.FILE_PROCESSING_TASK;
import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE;
import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE_CONTAINER_INFO;
import static com.streamarr.server.jooq.generated.Tables.MEDIA_FILE_STREAM_INFO;
import static org.jooq.impl.DSL.currentOffsetDateTime;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.greatest;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.val;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.ProbeClaim;
import com.streamarr.server.domain.task.ProbePublication;
import com.streamarr.server.domain.task.ProbeRequest;
import com.streamarr.server.jooq.generated.enums.FileProcessingTaskStatus;
import com.streamarr.server.jooq.generated.tables.records.FileProcessingTaskRecord;
import com.streamarr.server.jooq.generated.tables.records.MediaFileStreamInfoRecord;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.UpdateSetMoreStep;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class FileProcessingTaskRepositoryCustomImpl implements FileProcessingTaskRepositoryCustom {

  private static final List<FileProcessingTaskStatus> ACTIVE_STATUSES =
      List.of(FileProcessingTaskStatus.PENDING, FileProcessingTaskStatus.PROCESSING);

  private final DSLContext context;
  private final EntityManager entityManager;

  @Override
  @Transactional(readOnly = true)
  public List<FileProcessingTask> findLegacyTasks(Optional<UUID> afterId, int limit) {
    var query =
        context
            .selectFrom(FILE_PROCESSING_TASK)
            .where(FILE_PROCESSING_TASK.MEDIA_FILE_ID.isNull())
            .and(
                FILE_PROCESSING_TASK.STATUS.in(
                    inline(FileProcessingTaskStatus.PENDING),
                    inline(FileProcessingTaskStatus.PROCESSING)))
            .and(afterId.map(FILE_PROCESSING_TASK.ID::gt).orElse(noCondition()))
            .orderBy(FILE_PROCESSING_TASK.ID)
            .limit(limit);
    return executeJooqQuery(entityManager, query, FileProcessingTask.class);
  }

  @Override
  @Transactional
  public void cancelTask(String filepathUri) {
    context
        .select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.FILEPATH_URI.eq(filepathUri))
        .forUpdate()
        .execute();
    context
        .deleteFrom(FILE_PROCESSING_TASK)
        .where(FILE_PROCESSING_TASK.FILEPATH_URI.eq(filepathUri))
        .and(
            FILE_PROCESSING_TASK
                .STATUS
                .eq(FileProcessingTaskStatus.PENDING)
                .or(
                    FILE_PROCESSING_TASK
                        .MEDIA_FILE_ID
                        .isNotNull()
                        .and(FILE_PROCESSING_TASK.STATUS.eq(FileProcessingTaskStatus.PROCESSING))))
        .execute();
  }

  @Override
  @Transactional
  public UUID enqueueProbe(ProbeRequest request) {
    context
        .select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(request.mediaFileId()))
        .forUpdate()
        .fetchSingle();
    var taskId = UUID.randomUUID();
    context
        .insertInto(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.ID, taskId)
        .set(FILE_PROCESSING_TASK.FILEPATH_URI, request.filepathUri())
        .set(FILE_PROCESSING_TASK.LIBRARY_ID, request.libraryId())
        .set(FILE_PROCESSING_TASK.MEDIA_FILE_ID, request.mediaFileId())
        .set(FILE_PROCESSING_TASK.SOURCE_SIZE, request.snapshot().size())
        .set(
            FILE_PROCESSING_TASK.SOURCE_MODIFIED_EPOCH_SECOND,
            request.snapshot().modifiedAt().getEpochSecond())
        .set(FILE_PROCESSING_TASK.SOURCE_MODIFIED_NANOS, request.snapshot().modifiedAt().getNano())
        .set(FILE_PROCESSING_TASK.PROBE_VERSION, request.probeVersion())
        .set(FILE_PROCESSING_TASK.STATUS, FileProcessingTaskStatus.PENDING)
        .set(FILE_PROCESSING_TASK.CREATED_ON, currentOffsetDateTime())
        .onConflict(FILE_PROCESSING_TASK.FILEPATH_URI)
        .where(
            FILE_PROCESSING_TASK.STATUS.in(
                inline(FileProcessingTaskStatus.PENDING),
                inline(FileProcessingTaskStatus.PROCESSING)))
        .doNothing()
        .execute();
    resetProbeInputs(request)
        .set(FILE_PROCESSING_TASK.MEDIA_FILE_ID, request.mediaFileId())
        .where(FILE_PROCESSING_TASK.FILEPATH_URI.eq(request.filepathUri()))
        .and(FILE_PROCESSING_TASK.STATUS.in(ACTIVE_STATUSES))
        .and(
            FILE_PROCESSING_TASK
                .SOURCE_SIZE
                .isDistinctFrom(request.snapshot().size())
                .or(
                    FILE_PROCESSING_TASK.SOURCE_MODIFIED_EPOCH_SECOND.isDistinctFrom(
                        request.snapshot().modifiedAt().getEpochSecond()))
                .or(
                    FILE_PROCESSING_TASK.SOURCE_MODIFIED_NANOS.isDistinctFrom(
                        request.snapshot().modifiedAt().getNano()))
                .or(FILE_PROCESSING_TASK.PROBE_VERSION.lt(request.probeVersion())))
        .execute();
    invalidateChangedOutcome(request);
    return context
        .select(FILE_PROCESSING_TASK.ID)
        .from(FILE_PROCESSING_TASK)
        .where(FILE_PROCESSING_TASK.FILEPATH_URI.eq(request.filepathUri()))
        .and(FILE_PROCESSING_TASK.STATUS.in(ACTIVE_STATUSES))
        .fetchSingle(FILE_PROCESSING_TASK.ID);
  }

  @Override
  @Transactional
  public Optional<ProbeClaim> claimProbeTask(String ownerInstanceId, Instant leaseExpiresAt) {
    var pending =
        context
            .select(FILE_PROCESSING_TASK.ID)
            .from(FILE_PROCESSING_TASK)
            .where(FILE_PROCESSING_TASK.MEDIA_FILE_ID.isNotNull())
            .and(
                FILE_PROCESSING_TASK
                    .STATUS
                    .eq(FileProcessingTaskStatus.PENDING)
                    .and(FILE_PROCESSING_TASK.RETRY_AT.le(databaseNow()))
                    .or(
                        FILE_PROCESSING_TASK
                            .STATUS
                            .eq(FileProcessingTaskStatus.PROCESSING)
                            .and(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT.le(databaseNow()))))
            .orderBy(FILE_PROCESSING_TASK.CREATED_ON, FILE_PROCESSING_TASK.ID)
            .limit(1)
            .forUpdate()
            .skipLocked()
            .fetchOptional(FILE_PROCESSING_TASK.ID);
    return pending.flatMap(
        id ->
            context
                .update(FILE_PROCESSING_TASK)
                .set(FILE_PROCESSING_TASK.STATUS, FileProcessingTaskStatus.PROCESSING)
                .set(FILE_PROCESSING_TASK.CLAIM_ID, UUID.randomUUID())
                .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, ownerInstanceId)
                .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, leaseExpiresAt.atOffset(ZoneOffset.UTC))
                .where(FILE_PROCESSING_TASK.ID.eq(id))
                .returning()
                .fetchOptional()
                .map(FileProcessingTaskRepositoryCustomImpl::toClaim));
  }

  @Override
  @Transactional
  public boolean retryProbe(ProbeClaim claim, String errorMessage, Instant retryAt) {
    if (!lockClaim(claim)) {
      return false;
    }

    return clearClaim()
            .set(FILE_PROCESSING_TASK.STATUS, FileProcessingTaskStatus.PENDING)
            .set(FILE_PROCESSING_TASK.ERROR_MESSAGE, errorMessage)
            .set(FILE_PROCESSING_TASK.RETRY_AT, retryAt.atOffset(ZoneOffset.UTC))
            .set(FILE_PROCESSING_TASK.RETRY_COUNT, FILE_PROCESSING_TASK.RETRY_COUNT.plus(1))
            .where(ownsClaim(claim))
            .execute()
        == 1;
  }

  private boolean lockClaim(ProbeClaim claim) {
    if (!lockMediaFile(claim.request().mediaFileId())) {
      return false;
    }

    // Evaluate lease predicates only after row-lock waits have finished.
    return context
        .select(FILE_PROCESSING_TASK.ID)
        .from(FILE_PROCESSING_TASK)
        .where(FILE_PROCESSING_TASK.ID.eq(claim.taskId()))
        .and(FILE_PROCESSING_TASK.MEDIA_FILE_ID.eq(claim.request().mediaFileId()))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  private boolean lockMediaFile(UUID mediaFileId) {
    return context
        .select(MEDIA_FILE.ID)
        .from(MEDIA_FILE)
        .where(MEDIA_FILE.ID.eq(mediaFileId))
        .forUpdate()
        .fetchOptional()
        .isPresent();
  }

  @Override
  @Transactional
  public boolean publishProbe(ProbePublication publication) {
    var claim = publication.claim();
    if (!claim.request().snapshot().equals(publication.snapshot())
        || claim.request().probeVersion() != publication.probeVersion()) {
      return false;
    }

    if (!lockClaim(claim)) {
      return false;
    }

    var request = claim.request();
    var newerOutcome =
        context
            .selectOne()
            .from(MEDIA_FILE_CONTAINER_INFO)
            .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(request.mediaFileId()))
            .and(containerSnapshotMatches(request.snapshot()))
            .and(MEDIA_FILE_CONTAINER_INFO.PROBE_VERSION.gt(request.probeVersion()));
    var completed =
        finishClaim(FileProcessingTaskStatus.COMPLETED, null)
            .where(ownsClaim(claim))
            .andNotExists(newerOutcome)
            .execute();
    if (completed == 0) {
      return false;
    }

    replaceOutcome(publication);
    return true;
  }

  @Override
  @Transactional
  public boolean completeProbe(ProbeClaim claim) {
    if (!lockClaim(claim)) {
      return false;
    }

    var request = claim.request();
    var matchingOutcome =
        context
            .selectOne()
            .from(MEDIA_FILE_CONTAINER_INFO)
            .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(request.mediaFileId()))
            .and(containerSnapshotMatches(request.snapshot()))
            .and(MEDIA_FILE_CONTAINER_INFO.PROBE_VERSION.eq(request.probeVersion()));
    return finishClaim(FileProcessingTaskStatus.COMPLETED, null)
            .where(ownsClaim(claim))
            .andExists(matchingOutcome)
            .execute()
        == 1;
  }

  @Override
  @Transactional
  public boolean failProbe(ProbeClaim claim, String errorMessage) {
    if (!lockClaim(claim)) {
      return false;
    }

    return finishClaim(FileProcessingTaskStatus.FAILED, errorMessage)
            .where(ownsClaim(claim))
            .execute()
        == 1;
  }

  @Override
  @Transactional
  public boolean rescheduleProbe(ProbeClaim claim, ProbeRequest replacement) {
    if (!claim.request().mediaFileId().equals(replacement.mediaFileId())
        || !claim.request().filepathUri().equals(replacement.filepathUri())
        || !claim.request().libraryId().equals(replacement.libraryId())
        || !lockClaim(claim)) {
      return false;
    }

    var rescheduled = resetProbeInputs(replacement).where(ownsClaim(claim)).execute() == 1;
    if (rescheduled) {
      invalidateChangedOutcome(replacement);
    }

    return rescheduled;
  }

  @Override
  @Transactional
  public boolean renewProbe(ProbeClaim claim, Instant leaseExpiresAt) {
    if (!lockClaim(claim)) {
      return false;
    }

    return context
            .update(FILE_PROCESSING_TASK)
            .set(
                FILE_PROCESSING_TASK.LEASE_EXPIRES_AT,
                greatest(
                    FILE_PROCESSING_TASK.LEASE_EXPIRES_AT,
                    val(leaseExpiresAt.atOffset(ZoneOffset.UTC))))
            .where(ownsClaim(claim))
            .execute()
        == 1;
  }

  private UpdateSetMoreStep<FileProcessingTaskRecord> clearClaim() {
    return context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.CLAIM_ID, (UUID) null)
        .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, (String) null)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, (OffsetDateTime) null);
  }

  private UpdateSetMoreStep<FileProcessingTaskRecord> resetProbeInputs(ProbeRequest request) {
    return clearClaim()
        .set(FILE_PROCESSING_TASK.SOURCE_SIZE, request.snapshot().size())
        .set(
            FILE_PROCESSING_TASK.SOURCE_MODIFIED_EPOCH_SECOND,
            request.snapshot().modifiedAt().getEpochSecond())
        .set(FILE_PROCESSING_TASK.SOURCE_MODIFIED_NANOS, request.snapshot().modifiedAt().getNano())
        .set(FILE_PROCESSING_TASK.PROBE_VERSION, request.probeVersion())
        .set(FILE_PROCESSING_TASK.STATUS, FileProcessingTaskStatus.PENDING)
        .set(FILE_PROCESSING_TASK.RETRY_AT, databaseNow())
        .set(FILE_PROCESSING_TASK.RETRY_COUNT, 0)
        .set(FILE_PROCESSING_TASK.ERROR_MESSAGE, (String) null);
  }

  private UpdateSetMoreStep<FileProcessingTaskRecord> finishClaim(
      FileProcessingTaskStatus status, String errorMessage) {
    return clearClaim()
        .set(FILE_PROCESSING_TASK.STATUS, status)
        .set(FILE_PROCESSING_TASK.COMPLETED_ON, databaseNow())
        .set(FILE_PROCESSING_TASK.ERROR_MESSAGE, errorMessage);
  }

  private static Condition containerSnapshotMatches(SourceFileSnapshot snapshot) {
    return MEDIA_FILE_CONTAINER_INFO
        .SOURCE_SIZE
        .eq(snapshot.size())
        .and(
            MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_EPOCH_SECOND.eq(
                snapshot.modifiedAt().getEpochSecond()))
        .and(MEDIA_FILE_CONTAINER_INFO.SOURCE_MODIFIED_NANOS.eq(snapshot.modifiedAt().getNano()));
  }

  private void invalidateChangedOutcome(ProbeRequest request) {
    context
        .deleteFrom(MEDIA_FILE_CONTAINER_INFO)
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(request.mediaFileId()))
        .and(containerSnapshotMatches(request.snapshot()).not())
        .execute();
  }

  private void replaceOutcome(ProbePublication publication) {
    var mediaFileId = publication.claim().request().mediaFileId();
    context
        .deleteFrom(MEDIA_FILE_CONTAINER_INFO)
        .where(MEDIA_FILE_CONTAINER_INFO.MEDIA_FILE_ID.eq(mediaFileId))
        .execute();
    var row = context.newRecord(MEDIA_FILE_CONTAINER_INFO);
    row.setMediaFileId(mediaFileId);
    row.setSourceSize(publication.snapshot().size());
    row.setSourceModifiedEpochSecond(publication.snapshot().modifiedAt().getEpochSecond());
    row.setSourceModifiedNanos(publication.snapshot().modifiedAt().getNano());
    row.setProbeVersion(publication.probeVersion());
    switch (publication.outcome()) {
      case ProbeOutcome.Failure(var error) -> row.setProbeError(error.name());
      case ProbeOutcome.Success(var container, _) -> {
        row.setFormat(container.format().orElse(null));
        row.setTotalBitrate(
            container.bitrate().isPresent() ? container.bitrate().getAsLong() : null);
        row.setDurationSeconds(container.duration().map(Duration::getSeconds).orElse(null));
        row.setDurationNanos(container.duration().map(Duration::getNano).orElse(null));
      }
    }

    context.executeInsert(row);
    if (publication.outcome() instanceof ProbeOutcome.Success success) {
      context
          .batchInsert(
              success.streams().stream().map(stream -> toStreamRow(mediaFileId, stream)).toList())
          .execute();
    }
  }

  private MediaFileStreamInfoRecord toStreamRow(UUID mediaFileId, StreamInfo stream) {
    var row = context.newRecord(MEDIA_FILE_STREAM_INFO);
    row.setMediaFileId(mediaFileId);
    row.setStreamIndex(stream.index());
    row.setCodecType(stream.codecType());
    row.setCodec(stream.codec().orElse(null));
    row.setWidth(stream.width().isPresent() ? stream.width().getAsInt() : null);
    row.setHeight(stream.height().isPresent() ? stream.height().getAsInt() : null);
    row.setFramerate(stream.framerate().isPresent() ? stream.framerate().getAsDouble() : null);
    row.setChannels(stream.channels().isPresent() ? stream.channels().getAsInt() : null);
    row.setBitrate(stream.bitrate().isPresent() ? stream.bitrate().getAsLong() : null);
    row.setLanguage(stream.language().orElse(null));
    row.setIsDefault(stream.isDefault());
    row.setIsForced(stream.isForced());
    return row;
  }

  private static Condition ownsClaim(ProbeClaim claim) {
    var request = claim.request();
    return FILE_PROCESSING_TASK
        .ID
        .eq(claim.taskId())
        .and(FILE_PROCESSING_TASK.CLAIM_ID.eq(claim.claimId()))
        .and(FILE_PROCESSING_TASK.STATUS.eq(FileProcessingTaskStatus.PROCESSING))
        .and(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT.gt(databaseNow()))
        .and(FILE_PROCESSING_TASK.MEDIA_FILE_ID.eq(request.mediaFileId()))
        .and(FILE_PROCESSING_TASK.SOURCE_SIZE.eq(request.snapshot().size()))
        .and(
            FILE_PROCESSING_TASK.SOURCE_MODIFIED_EPOCH_SECOND.eq(
                request.snapshot().modifiedAt().getEpochSecond()))
        .and(
            FILE_PROCESSING_TASK.SOURCE_MODIFIED_NANOS.eq(
                request.snapshot().modifiedAt().getNano()))
        .and(FILE_PROCESSING_TASK.PROBE_VERSION.eq(request.probeVersion()));
  }

  private static Field<OffsetDateTime> databaseNow() {
    return function("clock_timestamp", FILE_PROCESSING_TASK.LEASE_EXPIRES_AT.getDataType());
  }

  private static ProbeClaim toClaim(FileProcessingTaskRecord task) {
    return ProbeClaim.builder()
        .taskId(task.getId())
        .claimId(task.getClaimId())
        .leaseExpiresAt(task.getLeaseExpiresAt().toInstant())
        .retryCount(task.getRetryCount())
        .request(
            ProbeRequest.builder()
                .mediaFileId(task.getMediaFileId())
                .libraryId(task.getLibraryId())
                .filepathUri(task.getFilepathUri())
                .snapshot(
                    new SourceFileSnapshot(
                        task.getSourceSize(),
                        Instant.ofEpochSecond(
                            task.getSourceModifiedEpochSecond(), task.getSourceModifiedNanos())))
                .probeVersion(task.getProbeVersion())
                .build())
        .build();
  }

  @Override
  @Transactional
  public Optional<FileProcessingTask> claimNextTask(
      String ownerInstanceId, Instant leaseExpiresAt) {
    var selectQuery =
        context
            .select(FILE_PROCESSING_TASK.ID)
            .from(FILE_PROCESSING_TASK)
            .where(FILE_PROCESSING_TASK.STATUS.eq(inline(FileProcessingTaskStatus.PENDING)))
            .and(FILE_PROCESSING_TASK.MEDIA_FILE_ID.isNull())
            .and(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID.isNull())
            .orderBy(FILE_PROCESSING_TASK.CREATED_ON.asc())
            .limit(1)
            .forUpdate()
            .skipLocked();

    var ids = executeJooqQuery(entityManager, selectQuery, UUID.class);

    if (ids.isEmpty()) {
      return Optional.empty();
    }

    var taskId = ids.getFirst();

    return context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.STATUS, inline(FileProcessingTaskStatus.PROCESSING))
        .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, ownerInstanceId)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, leaseExpiresAt.atOffset(ZoneOffset.UTC))
        .where(FILE_PROCESSING_TASK.ID.eq(taskId))
        .returning()
        .fetchOptional()
        .map(FileProcessingTaskRepositoryCustomImpl::toEntity);
  }

  @Override
  @Transactional
  public List<FileProcessingTask> reclaimOrphanedTasks(
      String ownerInstanceId, Instant leaseExpiresAt, Instant now, int limit) {
    var selectQuery =
        context
            .select(FILE_PROCESSING_TASK.ID)
            .from(FILE_PROCESSING_TASK)
            .where(
                FILE_PROCESSING_TASK.STATUS.in(
                    inline(FileProcessingTaskStatus.PENDING),
                    inline(FileProcessingTaskStatus.PROCESSING)))
            .and(FILE_PROCESSING_TASK.MEDIA_FILE_ID.isNull())
            .and(
                FILE_PROCESSING_TASK
                    .LEASE_EXPIRES_AT
                    .isNull()
                    .or(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT.lt(now.atOffset(ZoneOffset.UTC))))
            .orderBy(FILE_PROCESSING_TASK.CREATED_ON.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked();

    var ids = executeJooqQuery(entityManager, selectQuery, UUID.class);

    if (ids.isEmpty()) {
      return List.of();
    }

    return context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.STATUS, inline(FileProcessingTaskStatus.PENDING))
        .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, ownerInstanceId)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, leaseExpiresAt.atOffset(ZoneOffset.UTC))
        .where(FILE_PROCESSING_TASK.ID.in(ids))
        .returning()
        .fetch()
        .map(FileProcessingTaskRepositoryCustomImpl::toEntity);
  }

  @Override
  @Transactional
  public int extendLeases(String ownerInstanceId, Instant newLeaseExpiresAt) {
    return context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, newLeaseExpiresAt.atOffset(ZoneOffset.UTC))
        .where(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID.eq(ownerInstanceId))
        .and(FILE_PROCESSING_TASK.MEDIA_FILE_ID.isNull())
        .and(FILE_PROCESSING_TASK.STATUS.in(ACTIVE_STATUSES))
        .execute();
  }

  @Override
  @Transactional
  public Optional<FileProcessingTask> completeTask(UUID taskId, Instant completedOn) {
    return context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.STATUS, inline(FileProcessingTaskStatus.COMPLETED))
        .set(FILE_PROCESSING_TASK.COMPLETED_ON, completedOn.atOffset(ZoneOffset.UTC))
        .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, (String) null)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, (OffsetDateTime) null)
        .where(FILE_PROCESSING_TASK.ID.eq(taskId))
        .and(FILE_PROCESSING_TASK.MEDIA_FILE_ID.isNull())
        .and(FILE_PROCESSING_TASK.STATUS.in(ACTIVE_STATUSES))
        .returning()
        .fetchOptional()
        .map(FileProcessingTaskRepositoryCustomImpl::toEntity);
  }

  @Override
  @Transactional
  public Optional<FileProcessingTask> failTask(
      UUID taskId, String errorMessage, Instant completedOn) {
    return context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.STATUS, inline(FileProcessingTaskStatus.FAILED))
        .set(FILE_PROCESSING_TASK.ERROR_MESSAGE, errorMessage)
        .set(FILE_PROCESSING_TASK.COMPLETED_ON, completedOn.atOffset(ZoneOffset.UTC))
        .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, (String) null)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, (OffsetDateTime) null)
        .where(FILE_PROCESSING_TASK.ID.eq(taskId))
        .and(FILE_PROCESSING_TASK.MEDIA_FILE_ID.isNull())
        .and(FILE_PROCESSING_TASK.STATUS.in(ACTIVE_STATUSES))
        .returning()
        .fetchOptional()
        .map(FileProcessingTaskRepositoryCustomImpl::toEntity);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static FileProcessingTask toEntity(FileProcessingTaskRecord taskRecord) {
    return FileProcessingTask.builder()
        .id(taskRecord.getId())
        .filepathUri(taskRecord.getFilepathUri())
        .libraryId(taskRecord.getLibraryId())
        .status(
            com.streamarr.server.domain.task.FileProcessingTaskStatus.valueOf(
                taskRecord.getStatus().getLiteral()))
        .ownerInstanceId(taskRecord.getOwnerInstanceId())
        .leaseExpiresAt(toInstant(taskRecord.getLeaseExpiresAt()))
        .errorMessage(taskRecord.getErrorMessage())
        .createdOn(toInstant(taskRecord.getCreatedOn()))
        .completedOn(toInstant(taskRecord.getCompletedOn()))
        .build();
  }

  private static Instant toInstant(OffsetDateTime odt) {
    return odt != null ? odt.toInstant() : null;
  }

  @SuppressWarnings("unchecked")
  private static <E> List<E> executeJooqQuery(EntityManager em, Query query, Class<E> type) {
    var result = em.createNativeQuery(query.getSQL(), type);
    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      result.setParameter(i + 1, values.get(i));
    }
    return result.getResultList();
  }
}
