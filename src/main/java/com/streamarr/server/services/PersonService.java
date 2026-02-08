package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PersonService {

  private final PersonRepository personRepository;
  private final MutexFactory<String> mutexFactory;
  private final ApplicationEventPublisher eventPublisher;

  public PersonService(
      PersonRepository personRepository,
      MutexFactoryProvider mutexFactoryProvider,
      ApplicationEventPublisher eventPublisher) {
    this.personRepository = personRepository;
    this.mutexFactory = mutexFactoryProvider.getMutexFactory();
    this.eventPublisher = eventPublisher;
  }

  @Transactional
  public List<Person> getOrCreatePersons(List<Person> persons) {
    return persons.stream().map(this::findOrCreatePerson).collect(Collectors.toList());
  }

  private Person findOrCreatePerson(Person person) {
    var mutex = mutexFactory.getMutex(person.getSourceId());

    mutex.lock();
    try {
      var existingPerson = personRepository.findPersonBySourceId(person.getSourceId());

      if (existingPerson.isPresent()) {
        var target = existingPerson.get();
        target.setName(person.getName());
        target.setProfilePath(person.getProfilePath());
        return personRepository.save(target);
      }

      var savedPerson = personRepository.save(person);
      publishImageEvent(savedPerson);
      return savedPerson;
    } finally {
      mutex.unlock();
    }
  }

  private void publishImageEvent(Person person) {
    if (person.getProfilePath() == null) {
      return;
    }

    eventPublisher.publishEvent(
        new MetadataEnrichedEvent(
            person.getId(),
            ImageEntityType.PERSON,
            List.of(new TmdbImageSource(ImageType.PROFILE, person.getProfilePath()))));
  }
}
