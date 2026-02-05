package com.streamarr.server.fakes;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.BaseAuditableEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.FluentQuery;

public class FakeJpaRepository<L extends BaseAuditableEntity> implements JpaRepository<L, UUID> {

  public final HashMap<UUID, L> database = new HashMap<>();

  @Override
  public List<L> findAll() {
    return database.values().stream().toList();
  }

  @Override
  public List<L> findAll(Sort sort) {
    throw new NotImplementedException();
  }

  @Override
  public Page<L> findAll(Pageable pageable) {
    throw new NotImplementedException();
  }

  @Override
  public List<L> findAllById(Iterable<UUID> ids) {
    List<L> entityList = new ArrayList<>();

    ids.forEach(
        id -> {
          var entity = findById(id);

          if (entity.isEmpty()) {
            return;
          }

          entityList.add(entity.get());
        });

    return entityList;
  }

  @Override
  public long count() {
    return database.size();
  }

  @Override
  public void deleteById(UUID id) {
    database.remove(id);
  }

  @Override
  public void delete(L entity) {
    database.remove(entity.getId());
  }

  @Override
  public void deleteAllById(Iterable<? extends UUID> uuids) {
    uuids.forEach(database::remove);
  }

  @Override
  public void deleteAll(Iterable<? extends L> entities) {
    entities.forEach(entity -> database.remove(entity.getId()));
  }

  @Override
  public void deleteAll() {
    database.clear();
  }

  @Override
  public <S extends L> S save(S entity) {
    var id = entity.getId() != null ? entity.getId() : UUID.randomUUID();

    entity.setId(id);

    if (entity.getCreatedOn() == null) {
      AuditFieldSetter.setCreatedOn(entity, Instant.now());
    }

    database.put(id, entity);

    return (S) database.get(id);
  }

  @Override
  public <S extends L> List<S> saveAll(Iterable<S> entities) {
    List<S> entityList = new ArrayList<>();

    entities.forEach(
        entity -> {
          entityList.add(save(entity));
        });

    return entityList;
  }

  @Override
  public Optional<L> findById(UUID uuid) {
    return Optional.ofNullable(database.get(uuid));
  }

  @Override
  public boolean existsById(UUID uuid) {
    return database.containsKey(uuid);
  }

  @Override
  public void flush() {}

  @Override
  public <S extends L> S saveAndFlush(S entity) {
    return save(entity);
  }

  @Override
  public <S extends L> List<S> saveAllAndFlush(Iterable<S> entities) {
    return saveAll(entities);
  }

  @Override
  public void deleteAllInBatch(Iterable<L> entities) {
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
  public L getOne(UUID uuid) {
    return database.get(uuid);
  }

  @Override
  public L getById(UUID uuid) {
    return database.get(uuid);
  }

  @Override
  public L getReferenceById(UUID uuid) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends L> Optional<S> findOne(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends L> List<S> findAll(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends L> List<S> findAll(Example<S> example, Sort sort) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends L> Page<S> findAll(Example<S> example, Pageable pageable) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends L> long count(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends L> boolean exists(Example<S> example) {
    throw new NotImplementedException();
  }

  @Override
  public <S extends L, R> R findBy(
      Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) {
    throw new NotImplementedException();
  }
}
