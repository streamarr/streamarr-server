package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

  /**
   * Returns the saved people in the order of {@code persons}. Each distinct source id is saved
   * once, from its first listing, and its artwork is requested once.
   */
  @Transactional
  public List<Person> getOrCreatePersons(
      List<Person> persons,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
    if (persons == null) {
      return List.of();
    }

    persons.forEach(PersonService::requirePerson);

    var firstListedBySourceId = new LinkedHashMap<String, Person>();
    persons.forEach(person -> firstListedBySourceId.putIfAbsent(person.getSourceId(), person));

    var savedBySourceId = new HashMap<String, Person>();
    CreditInsertOrder.sortedBySourceId(
            firstListedBySourceId.values(), Person::getSourceId, "Person")
        .forEach(
            person ->
                savedBySourceId.put(
                    person.getSourceId(),
                    findOrCreatePerson(person, imageSourcesBySourceId, imageRefreshMode)));

    return persons.stream().map(person -> savedBySourceId.get(person.getSourceId())).toList();
  }

  private Person findOrCreatePerson(
      Person person,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
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

    publishImageEvent(saved, imageSources, imageRefreshMode);
    return saved;
  }

  private static void requirePerson(Person person) {
    if (person == null) {
      throw new IllegalArgumentException("Person must not be null");
    }
  }

  private void publishImageEvent(
      Person person, List<ImageSource> imageSources, ImageRefreshMode imageRefreshMode) {
    if (imageSources.isEmpty()) {
      return;
    }

    eventPublisher.publishEvent(
        new MetadataEnrichedEvent(
            person.getId(), ImageEntityType.PERSON, imageSources, imageRefreshMode));
  }
}
