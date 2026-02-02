package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Person;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PersonRepository extends JpaRepository<Person, UUID> {

  Set<Person> findPersonsBySourceIdIn(List<String> sourceIds);

  Optional<Person> findPersonBySourceId(String sourceId);
}
