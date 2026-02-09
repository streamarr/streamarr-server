package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Map;
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
  public List<Person> getOrCreatePersons(
      List<Person> persons, Map<String, List<ImageSource>> imageSourcesBySourceId) {
    if (persons == null) {
      return List.of();
    }

    return persons.stream()
        .map(p -> findOrCreatePerson(p, imageSourcesBySourceId))
        .collect(Collectors.toList());
  }

  private Person findOrCreatePerson(
      Person person, Map<String, List<ImageSource>> imageSourcesBySourceId) {
    if (person.getSourceId() == null) {
      throw new IllegalArgumentException("Person sourceId must not be null");
    }

    var imageSources = imageSourcesBySourceId.getOrDefault(person.getSourceId(), List.of());
    var mutex = mutexFactory.getMutex(person.getSourceId());

    mutex.lock();
    try {
      var existingPerson = personRepository.findPersonBySourceId(person.getSourceId());

      if (existingPerson.isPresent()) {
        var target = existingPerson.get();
        target.setName(person.getName());
        return personRepository.save(target);
      }

      var savedPerson = personRepository.save(person);
      publishImageEvent(savedPerson, imageSources);
      return savedPerson;
    } finally {
      mutex.unlock();
    }
  }

  private void publishImageEvent(Person person, List<ImageSource> imageSources) {
    if (imageSources.isEmpty()) {
      return;
    }

    eventPublisher.publishEvent(
        new MetadataEnrichedEvent(person.getId(), ImageEntityType.PERSON, imageSources));
  }
}
