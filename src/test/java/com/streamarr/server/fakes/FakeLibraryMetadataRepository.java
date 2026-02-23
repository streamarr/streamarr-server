package com.streamarr.server.fakes;

import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.repositories.LibraryMetadataRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;
import java.util.function.Function;

public class FakeLibraryMetadataRepository implements LibraryMetadataRepository {

  private final ConcurrentHashMap<UUID, LibraryMetadata> database = new ConcurrentHashMap<>();

  @Override
  public List<LibraryMetadata> findByLibraryIdOrderByLetterAsc(UUID libraryId) {
    return database.values().stream()
        .filter(m -> m.getLibraryId().equals(libraryId))
        .sorted(Comparator.comparing(m -> m.getLetter().ordinal()))
        .toList();
  }

  @Override
  public void deleteByLibraryId(UUID libraryId) {
    database.entrySet().removeIf(e -> e.getValue().getLibraryId().equals(libraryId));
  }

  @Override
  public <S extends LibraryMetadata> S save(S entity) {
    var id = entity.getId() != null ? entity.getId() : UUID.randomUUID();
    entity.setId(id);
    database.put(id, entity);
    @SuppressWarnings("unchecked")
    S result = (S) database.get(id);
    return result;
  }

  @Override
  public <S extends LibraryMetadata> List<S> saveAll(Iterable<S> entities) {
    List<S> result = new ArrayList<>();
    entities.forEach(e -> result.add(save(e)));
    return result;
  }

  @Override
  public Optional<LibraryMetadata> findById(UUID id) {
    return Optional.ofNullable(database.get(id));
  }

  @Override
  public List<LibraryMetadata> findAll() {
    return new ArrayList<>(database.values());
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
  public void delete(LibraryMetadata entity) {
    database.remove(entity.getId());
  }

  @Override
  public void deleteAll() {
    database.clear();
  }

  @Override
  public void deleteAll(Iterable<? extends LibraryMetadata> entities) {
    entities.forEach(e -> database.remove(e.getId()));
  }

  @Override public boolean existsById(UUID id) { return database.containsKey(id); }
  @Override public List<LibraryMetadata> findAllById(Iterable<UUID> ids) { throw new NotImplementedException(); }
  @Override public void flush() {}
  @Override public <S extends LibraryMetadata> S saveAndFlush(S entity) { return save(entity); }
  @Override public <S extends LibraryMetadata> List<S> saveAllAndFlush(Iterable<S> entities) { return saveAll(entities); }
  @Override public void deleteAllInBatch(Iterable<LibraryMetadata> entities) { throw new NotImplementedException(); }
  @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw new NotImplementedException(); }
  @Override public void deleteAllInBatch() { throw new NotImplementedException(); }
  @Override public LibraryMetadata getOne(UUID id) { throw new NotImplementedException(); }
  @Override public LibraryMetadata getById(UUID id) { return database.get(id); }
  @Override public LibraryMetadata getReferenceById(UUID id) { throw new NotImplementedException(); }
  @Override public <S extends LibraryMetadata> Optional<S> findOne(Example<S> example) { throw new NotImplementedException(); }
  @Override public <S extends LibraryMetadata> List<S> findAll(Example<S> example) { throw new NotImplementedException(); }
  @Override public <S extends LibraryMetadata> List<S> findAll(Example<S> example, Sort sort) { throw new NotImplementedException(); }
  @Override public <S extends LibraryMetadata> Page<S> findAll(Example<S> example, Pageable pageable) { throw new NotImplementedException(); }
  @Override public <S extends LibraryMetadata> long count(Example<S> example) { throw new NotImplementedException(); }
  @Override public <S extends LibraryMetadata> boolean exists(Example<S> example) { throw new NotImplementedException(); }
  @Override public <S extends LibraryMetadata, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new NotImplementedException(); }
  @Override public List<LibraryMetadata> findAll(Sort sort) { throw new NotImplementedException(); }
  @Override public Page<LibraryMetadata> findAll(Pageable pageable) { throw new NotImplementedException(); }
  @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw new NotImplementedException(); }
}
