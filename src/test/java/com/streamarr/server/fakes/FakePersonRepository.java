package com.streamarr.server.fakes;

import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.commons.lang3.NotImplementedException;

public class FakePersonRepository extends FakeJpaRepository<Person> implements PersonRepository {

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
}
