package com.streamarr.server.fakes;

import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Setter;
import org.apache.commons.lang3.NotImplementedException;

public class FakePersonRepository extends FakeJpaRepository<Person> implements PersonRepository {

  @Setter private boolean simulateConflict;

  @Override
  public boolean insertIfAbsent(String sourceId, String name) {
    if (simulateConflict) {
      save(Person.builder().sourceId(sourceId).name(name).build());
      return false;
    }
    boolean exists =
        database.values().stream().anyMatch(p -> sourceId.equals(p.getSourceId()));
    if (exists) {
      return false;
    }
    save(Person.builder().sourceId(sourceId).name(name).build());
    return true;
  }

  @Override
  public Optional<Person> findPersonBySourceId(String sourceId) {
    return database.values().stream()
        .filter(person -> sourceId.equals(person.getSourceId()))
        .findFirst();
  }

  @Override
  public Set<Person> findPersonsBySourceIdIn(List<String> sourceIds) {
    throw new NotImplementedException();
  }

  @Override
  public List<Person> findCastByMovieId(UUID movieId) {
    throw new NotImplementedException();
  }

  @Override
  public List<Person> findDirectorsByMovieId(UUID movieId) {
    throw new NotImplementedException();
  }

  @Override
  public List<Person> findCastBySeriesId(UUID seriesId) {
    throw new NotImplementedException();
  }

  @Override
  public List<Person> findDirectorsBySeriesId(UUID seriesId) {
    throw new NotImplementedException();
  }
}
