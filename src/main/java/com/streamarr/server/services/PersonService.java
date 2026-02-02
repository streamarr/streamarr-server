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

  @Transactional
  public List<Person> getOrCreatePersons(List<Person> persons) {
    return persons.stream().map(this::savePerson).collect(Collectors.toList());
  }

  private Person savePerson(Person person) {
    var existingPerson = personRepository.findPersonBySourceId(person.getSourceId());

    if (existingPerson.isPresent()) {
      personMappers.updatePerson(person, existingPerson.get());
      return personRepository.save(existingPerson.get());
    }

    return personRepository.save(person);
  }
}
