package com.streamarr.server.services;

import com.streamarr.server.domain.mappers.PersonMappers;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersonService {

  private final PersonRepository personRepository;
  private final PersonMappers personMappers;

  public List<Person> getOrCreateCast(List<Person> cast) {
    return cast.stream().map(this::savePerson).collect(Collectors.toList());
  }

  @Transactional
  public Person savePerson(Person person) {
    var existingPerson = personRepository.findPersonBySourceId(person.getSourceId());

    if (existingPerson != null) {
      personMappers.updatePerson(person, existingPerson);
      return personRepository.save(existingPerson);
    }

    return personRepository.save(person);
  }
}
