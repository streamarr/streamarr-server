package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersonService {

  private final PersonRepository personRepository;
  private final ApplicationEventPublisher eventPublisher;

  @Transactional
  public List<Person> getOrCreatePersons(
      List<Person> persons, Map<String, List<ImageSource>> imageSourcesBySourceId) {
    if (persons == null) {
      return List.of();
    }

    persons.forEach(PersonService::requireSourceId);

    var savedBySourceId = new HashMap<String, Person>();
    persons.stream()
        .sorted(Comparator.comparing(Person::getSourceId))
        .forEach(
            person ->
                savedBySourceId.put(
                    person.getSourceId(), findOrCreatePerson(person, imageSourcesBySourceId)));

    return persons.stream().map(person -> savedBySourceId.get(person.getSourceId())).toList();
  }

  private Person findOrCreatePerson(
      Person person, Map<String, List<ImageSource>> imageSourcesBySourceId) {
    var imageSources = imageSourcesBySourceId.getOrDefault(person.getSourceId(), List.of());

    personRepository.insertIfAbsent(person.getSourceId(), person.getName());
    var saved =
        personRepository
            .findPersonBySourceId(person.getSourceId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Person not found after upsert for sourceId: " + person.getSourceId()));

    saved.setName(person.getName());

    publishImageEvent(saved, imageSources);
    return saved;
  }

  private static void requireSourceId(Person person) {
    if (person == null) {
      throw new IllegalArgumentException("Person must not be null");
    }

    if (person.getSourceId() == null) {
      throw new IllegalArgumentException("Person sourceId must not be null");
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
