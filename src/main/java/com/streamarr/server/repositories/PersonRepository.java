package com.streamarr.server.repositories;

import com.streamarr.server.domain.metadata.Person;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;


@Repository
public interface PersonRepository extends JpaRepository<Person, UUID> {

    Set<Person> findPersonsBySourceIdIn(List<String> sourceIds);

    Person findPersonBySourceId(String sourceId);
}
