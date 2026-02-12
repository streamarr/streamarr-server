package com.streamarr.server.fakes;

import com.streamarr.server.domain.task.FileProcessingTask;
import com.streamarr.server.domain.task.FileProcessingTaskStatus;
import com.streamarr.server.repositories.task.FileProcessingTaskRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

public class FakeFileProcessingTaskRepository implements FileProcessingTaskRepository {

  private static final List<FileProcessingTaskStatus> ACTIVE_STATUSES =
      List.of(FileProcessingTaskStatus.PENDING, FileProcessingTaskStatus.PROCESSING);

  private final Map<UUID, FileProcessingTask> database = new HashMap<>();

  @Override
  public Optional<FileProcessingTask> findByFilepathAndStatusIn(
      String filepath, List<FileProcessingTaskStatus> statuses) {
    return database.values().stream()
        .filter(task -> filepath.equals(task.getFilepath()))
        .filter(task -> statuses.contains(task.getStatus()))
        .findFirst();
  }

  @Override
  public List<FileProcessingTask> findByOwnerInstanceId(String ownerInstanceId) {
    return database.values().stream()
        .filter(task -> ownerInstanceId.equals(task.getOwnerInstanceId()))
        .toList();
  }

  @Override
  public void deleteByFilepathAndStatusIn(
      String filepath, List<FileProcessingTaskStatus> statuses) {
    database
        .entrySet()
        .removeIf(
            entry ->
                filepath.equals(entry.getValue().getFilepath())
                    && statuses.contains(entry.getValue().getStatus()));
  }

  @Override
  public Optional<FileProcessingTask> claimNextTask(
      String ownerInstanceId, Instant leaseExpiresAt) {
    return database.values().stream()
        .filter(task -> task.getStatus() == FileProcessingTaskStatus.PENDING)
        .filter(task -> task.getOwnerInstanceId() == null)
        .findFirst()
        .map(
            task -> {
              task.setOwnerInstanceId(ownerInstanceId);
              task.setLeaseExpiresAt(leaseExpiresAt);
              task.setStatus(FileProcessingTaskStatus.PROCESSING);
              return task;
            });
  }

  @Override
  public List<FileProcessingTask> reclaimOrphanedTasks(
      String ownerInstanceId, Instant leaseExpiresAt, Instant now, int limit) {
    return database.values().stream()
        .filter(task -> ACTIVE_STATUSES.contains(task.getStatus()))
        .filter(task -> task.getLeaseExpiresAt() == null || task.getLeaseExpiresAt().isBefore(now))
        .limit(limit)
        .map(
            task -> {
              task.setOwnerInstanceId(ownerInstanceId);
              task.setLeaseExpiresAt(leaseExpiresAt);
              task.setStatus(FileProcessingTaskStatus.PENDING);
              return task;
            })
        .toList();
  }

  @Override
  public int extendLeases(String ownerInstanceId, Instant newLeaseExpiresAt) {
    var tasks =
        database.values().stream()
            .filter(task -> ownerInstanceId.equals(task.getOwnerInstanceId()))
            .filter(task -> ACTIVE_STATUSES.contains(task.getStatus()))
            .toList();
    tasks.forEach(task -> task.setLeaseExpiresAt(newLeaseExpiresAt));
    return tasks.size();
  }

  @Override
  public <S extends FileProcessingTask> S save(S entity) {
    var id = entity.getId() != null ? entity.getId() : UUID.randomUUID();
    entity.setId(id);
    database.put(id, entity);
    return entity;
  }

  @Override
  public <S extends FileProcessingTask> List<S> saveAll(Iterable<S> entities) {
    var result = new ArrayList<S>();
    entities.forEach(
        entity -> {
          result.add(save(entity));
        });
    return result;
  }

  @Override
  public Optional<FileProcessingTask> findById(UUID uuid) {
    return Optional.ofNullable(database.get(uuid));
  }

  @Override
  public boolean existsById(UUID uuid) {
    return database.containsKey(uuid);
  }

  @Override
  public List<FileProcessingTask> findAll() {
    return new ArrayList<>(database.values());
  }

  @Override
  public List<FileProcessingTask> findAllById(Iterable<UUID> uuids) {
    var result = new ArrayList<FileProcessingTask>();
    uuids.forEach(
        uuid -> {
          var entity = findById(uuid);
          entity.ifPresent(result::add);
        });
    return result;
  }

  @Override
  public long count() {
    return database.size();
  }

  @Override
  public void deleteById(UUID uuid) {
    database.remove(uuid);
  }

  @Override
  public void delete(FileProcessingTask entity) {
    database.remove(entity.getId());
  }

  @Override
  public void deleteAllById(Iterable<? extends UUID> uuids) {
    uuids.forEach(database::remove);
  }

  @Override
  public void deleteAll(Iterable<? extends FileProcessingTask> entities) {
    entities.forEach(entity -> database.remove(entity.getId()));
  }

  @Override
  public void deleteAll() {
    database.clear();
  }

  @Override
  public void flush() {
    // no-op for test fake
  }

  @Override
  public <S extends FileProcessingTask> S saveAndFlush(S entity) {
    return save(entity);
  }

  @Override
  public <S extends FileProcessingTask> List<S> saveAllAndFlush(Iterable<S> entities) {
    return saveAll(entities);
  }

  @Override
  public List<FileProcessingTask> findAll(Sort sort) {
    throw new NotImplementedException();
  }

  @Override
  public Page<FileProcessingTask> findAll(Pageable pageable) {
    throw new NotImplementedException();
  }

  @Override
  public void deleteAllInBatch(Iterable<FileProcessingTask> entities) {
    throw new NotImplementedException();
  }

  @Override
  public void deleteAllByIdInBatch(Iterable<UUID> uuids) {
    throw new NotImplementedException();
  }

  @Override
  public void deleteAllInBatch() {
    throw new NotImplementedException();
  }

  @Override
  public FileProcessingTask getOne(UUID uuid) {
    return database.get(uuid);
  }

  @Override
  public FileProcessingTask getById(UUID uuid) {
    return database.get(uuid);
  }

  @Override
  public FileProcessingTask getReferenceById(UUID uuid) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends FileProcessingTask> Optional<S> findOne(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends FileProcessingTask> List<S> findAll(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends FileProcessingTask> List<S> findAll(Example<S> example, Sort sort) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends FileProcessingTask> Page<S> findAll(Example<S> example, Pageable pageable) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends FileProcessingTask> long count(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends FileProcessingTask> boolean exists(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends FileProcessingTask, R> R findBy(
      Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
    throw new NotImplementedException();
  }
}
