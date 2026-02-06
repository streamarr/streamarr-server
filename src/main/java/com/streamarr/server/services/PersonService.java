package com.streamarr.server.services;

import com.streamarr.server.domain.mappers.PersonMappers;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonService {

  private final PersonRepository personRepository;
  private final PersonMappers personMappers;
  private final MutexFactory<String> mutexFactory;

  public PersonService(
      PersonRepository personRepository,
      PersonMappers personMappers,
      MutexFactoryProvider mutexFactoryProvider) {
    this.personRepository = personRepository;
    this.personMappers = personMappers;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
  }

  @Transactional
  public List<Person> getOrCreatePersons(List<Person> persons) {
    return persons.stream().map(this::findOrCreatePerson).collect(Collectors.toList());
  }

  private Person findOrCreatePerson(Person person) {
    var mutex = mutexFactory.getMutex(person.getSourceId());

    try {
      mutex.lock();

      var existingPerson = personRepository.findPersonBySourceId(person.getSourceId());

      if (existingPerson.isPresent()) {
        personMappers.updatePerson(person, existingPerson.get());
        return personRepository.save(existingPerson.get());
      }

      return personRepository.save(person);
    } finally {
      if (mutex.isHeldByCurrentThread()) {
        mutex.unlock();
      }
    }
  }
}
