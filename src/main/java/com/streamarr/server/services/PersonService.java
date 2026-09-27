package com.streamarr.server.services;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
    return getOrCreatePersonsInternal(persons, imageSourcesBySourceId, ImageRefreshMode.PRESERVE);
  }

  @Transactional
  public List<Person> getOrCreatePersons(
      List<Person> persons,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
    return getOrCreatePersonsInternal(persons, imageSourcesBySourceId, imageRefreshMode);
  }

  /** A movie's cast and directors after one upsert pass. */
  public record Credits(List<Person> cast, List<Person> directors) {}

  /**
   * Upserts cast and directors together in one pass ordered by source id, so every transaction
   * that creates people inserts their keys in the same global order. Two separately sorted passes
   * (cast, then directors) do not give that order, and concurrent inserts of the same new keys in
   * different orders deadlock in PostgreSQL. Each list keeps its order and its per-entry name and
   * image event, as {@link #getOrCreatePersons} does.
   */
  @Transactional
  public Credits getOrCreateCredits(
      List<Person> cast,
      List<Person> directors,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
    var castList = cast == null ? List.<Person>of() : cast;
    var directorList = directors == null ? List.<Person>of() : directors;
    castList.forEach(PersonService::requireSourceId);
    directorList.forEach(PersonService::requireSourceId);

    var firstBySourceId = new LinkedHashMap<String, Person>();
    Stream.concat(castList.stream(), directorList.stream())
        .forEach(person -> firstBySourceId.putIfAbsent(person.getSourceId(), person));

    var savedBySourceId = new HashMap<String, Person>();
    firstBySourceId.values().stream()
        .sorted(Comparator.comparing(Person::getSourceId))
        .forEach(person -> savedBySourceId.put(person.getSourceId(), upsert(person)));

    return new Credits(
        applyCredits(castList, savedBySourceId, imageSourcesBySourceId, imageRefreshMode),
        applyCredits(directorList, savedBySourceId, imageSourcesBySourceId, imageRefreshMode));
  }

  private List<Person> applyCredits(
      List<Person> persons,
      Map<String, Person> savedBySourceId,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
    persons.stream()
        .sorted(Comparator.comparing(Person::getSourceId))
        .forEach(
            person -> {
              var saved = savedBySourceId.get(person.getSourceId());
              saved.setName(person.getName());
              publishImageEvent(
                  saved,
                  imageSourcesBySourceId.getOrDefault(person.getSourceId(), List.of()),
                  imageRefreshMode);
            });

    return persons.stream().map(person -> savedBySourceId.get(person.getSourceId())).toList();
  }

  private Person upsert(Person person) {
    personRepository.insertIfAbsent(person.getSourceId(), person.getName());
    return personRepository
        .findPersonBySourceId(person.getSourceId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Person not found after upsert for sourceId: " + person.getSourceId()));
  }

  private List<Person> getOrCreatePersonsInternal(
      List<Person> persons,
      Map<String, List<ImageSource>> imageSourcesBySourceId,
      ImageRefreshMode imageRefreshMode) {
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

  private static void requireSourceId(Person person) {
    if (person == null) {
      throw new IllegalArgumentException("Person must not be null");
    }

    if (person.getSourceId() == null) {
      throw new IllegalArgumentException("Person sourceId must not be null");
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
