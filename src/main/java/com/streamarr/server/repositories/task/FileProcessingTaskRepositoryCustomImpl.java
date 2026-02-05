package com.streamarr.server.repositories.task;

import static com.streamarr.server.jooq.generated.Tables.FILE_PROCESSING_TASK;
import static org.jooq.impl.DSL.inline;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.jooq.generated.enums.FileProcessingTaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class FileProcessingTaskRepositoryCustomImpl implements FileProcessingTaskRepositoryCustom {

  private static final List<FileProcessingTaskStatus> ACTIVE_STATUSES =
      List.of(FileProcessingTaskStatus.PENDING, FileProcessingTaskStatus.PROCESSING);

  private final DSLContext context;
  @PersistenceContext private final EntityManager entityManager;

  @Override
  @Transactional
  public Optional<FileProcessingTask> claimNextTask(String ownerInstanceId, Instant leaseExpiresAt) {
    var selectQuery =
        context
            .select(FILE_PROCESSING_TASK.ID)
            .from(FILE_PROCESSING_TASK)
            .where(FILE_PROCESSING_TASK.STATUS.eq(inline(FileProcessingTaskStatus.PENDING)))
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

    context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.STATUS, inline(FileProcessingTaskStatus.PROCESSING))
        .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, ownerInstanceId)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, leaseExpiresAt.atOffset(ZoneOffset.UTC))
        .where(FILE_PROCESSING_TASK.ID.eq(taskId))
        .execute();

    return Optional.of(entityManager.find(FileProcessingTask.class, taskId));
  }

  @Override
  @Transactional
  public List<FileProcessingTask> reclaimOrphanedTasks(
      String ownerInstanceId, Instant leaseExpiresAt, Instant now, int limit) {
    var selectQuery =
        context
            .select(FILE_PROCESSING_TASK.ID)
            .from(FILE_PROCESSING_TASK)
            .where(FILE_PROCESSING_TASK.STATUS.in(
                inline(FileProcessingTaskStatus.PENDING), inline(FileProcessingTaskStatus.PROCESSING)))
            .and(
                FILE_PROCESSING_TASK.LEASE_EXPIRES_AT.isNull()
                    .or(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT.lt(now.atOffset(ZoneOffset.UTC))))
            .orderBy(FILE_PROCESSING_TASK.CREATED_ON.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked();

    var ids = executeJooqQuery(entityManager, selectQuery, UUID.class);

    if (ids.isEmpty()) {
      return List.of();
    }

    context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.STATUS, inline(FileProcessingTaskStatus.PENDING))
        .set(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID, ownerInstanceId)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, leaseExpiresAt.atOffset(ZoneOffset.UTC))
        .where(FILE_PROCESSING_TASK.ID.in(ids))
        .execute();

    return ids.stream().map(id -> entityManager.find(FileProcessingTask.class, id)).toList();
  }

  @Override
  @Transactional
  public int extendLeases(String ownerInstanceId, Instant newLeaseExpiresAt) {
    return context
        .update(FILE_PROCESSING_TASK)
        .set(FILE_PROCESSING_TASK.LEASE_EXPIRES_AT, newLeaseExpiresAt.atOffset(ZoneOffset.UTC))
        .where(FILE_PROCESSING_TASK.OWNER_INSTANCE_ID.eq(ownerInstanceId))
        .and(FILE_PROCESSING_TASK.STATUS.in(ACTIVE_STATUSES))
        .execute();
  }

  @SuppressWarnings("unchecked")
  private static <E> List<E> executeJooqQuery(EntityManager em, org.jooq.Query query, Class<E> type) {
    Query result = em.createNativeQuery(query.getSQL(), type);
    List<Object> values = query.getBindValues();
    for (int i = 0; i < values.size(); i++) {
      result.setParameter(i + 1, values.get(i));
    }
    return result.getResultList();
  }
}
