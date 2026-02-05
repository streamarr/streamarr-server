package com.streamarr.server.repositories.task;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
public class FileProcessingTaskRepositoryCustomImpl implements FileProcessingTaskRepositoryCustom {

  @PersistenceContext private final EntityManager entityManager;

  @Override
  @Transactional
  public Optional<FileProcessingTask> claimNextTask(String ownerInstanceId, Instant leaseExpiresAt) {
    var selectSql =
        """
        SELECT id FROM file_processing_task
        WHERE status = 'PENDING'
          AND owner_instance_id IS NULL
        ORDER BY created_on ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """;

    var selectQuery = entityManager.createNativeQuery(selectSql);

    @SuppressWarnings("unchecked")
    var ids = (List<java.util.UUID>) selectQuery.getResultList();

    if (ids.isEmpty()) {
      return Optional.empty();
    }

    var taskId = ids.getFirst();

    var updateSql =
        """
        UPDATE file_processing_task
        SET status = 'PROCESSING',
            owner_instance_id = :ownerInstanceId,
            lease_expires_at = :leaseExpiresAt
        WHERE id = :id
        """;

    entityManager
        .createNativeQuery(updateSql)
        .setParameter("ownerInstanceId", ownerInstanceId)
        .setParameter("leaseExpiresAt", leaseExpiresAt)
        .setParameter("id", taskId)
        .executeUpdate();

    return Optional.of(entityManager.find(FileProcessingTask.class, taskId));
  }

  @Override
  @Transactional
  public List<FileProcessingTask> reclaimOrphanedTasks(
      String ownerInstanceId, Instant leaseExpiresAt, Instant now, int limit) {
    var selectSql =
        """
        SELECT id FROM file_processing_task
        WHERE status IN ('PENDING', 'PROCESSING')
          AND lease_expires_at < :now
        ORDER BY created_on ASC
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
        """;

    @SuppressWarnings("unchecked")
    var ids =
        (List<java.util.UUID>)
            entityManager
                .createNativeQuery(selectSql)
                .setParameter("now", now)
                .setParameter("limit", limit)
                .getResultList();

    if (ids.isEmpty()) {
      return List.of();
    }

    var updateSql =
        """
        UPDATE file_processing_task
        SET status = 'PENDING',
            owner_instance_id = :ownerInstanceId,
            lease_expires_at = :leaseExpiresAt
        WHERE id IN :ids
        """;

    entityManager
        .createNativeQuery(updateSql)
        .setParameter("ownerInstanceId", ownerInstanceId)
        .setParameter("leaseExpiresAt", leaseExpiresAt)
        .setParameter("ids", ids)
        .executeUpdate();

    return ids.stream()
        .map(id -> entityManager.find(FileProcessingTask.class, id))
        .toList();
  }

  @Override
  @Transactional
  public int extendLeases(String ownerInstanceId, Instant newLeaseExpiresAt) {
    var sql =
        """
        UPDATE file_processing_task
        SET lease_expires_at = :newLeaseExpiresAt
        WHERE owner_instance_id = :ownerInstanceId
          AND status IN ('PENDING', 'PROCESSING')
        """;

    return entityManager
        .createNativeQuery(sql)
        .setParameter("newLeaseExpiresAt", newLeaseExpiresAt)
        .setParameter("ownerInstanceId", ownerInstanceId)
        .executeUpdate();
  }
}
