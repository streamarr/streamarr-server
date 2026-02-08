package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakePersonRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Person Service Tests")
class PersonServiceTest {

  private FakePersonRepository personRepository;
  private CapturingEventPublisher eventPublisher;
  private PersonService personService;

  @BeforeEach
  void setUp() {
    personRepository = new FakePersonRepository();
    eventPublisher = new CapturingEventPublisher();
    personService = new PersonService(personRepository, new MutexFactoryProvider(), eventPublisher);
  }

  @Test
  @DisplayName("Should create new person when source ID not found")
  void shouldCreateNewPersonWhenSourceIdNotFound() {
    var person = Person.builder().name("Tom Hanks").sourceId("actor-1").build();

    var result = personService.getOrCreatePersons(List.of(person), Map.of());

    assertThat(result).hasSize(1);
    var saved = result.getFirst();
    assertThat(saved.getName()).isEqualTo("Tom Hanks");
    assertThat(saved.getSourceId()).isEqualTo("actor-1");
    assertThat(saved.getId()).isNotNull();
    assertThat(personRepository.database).hasSize(1);
  }

  @Test
  @DisplayName("Should update existing person when source ID already exists")
  void shouldUpdateExistingPersonWhenSourceIdAlreadyExists() {
    var existing =
        personRepository.save(Person.builder().name("Old Name").sourceId("actor-1").build());

    var updated = Person.builder().name("New Name").sourceId("actor-1").build();

    var result = personService.getOrCreatePersons(List.of(updated), Map.of());

    assertThat(result).hasSize(1);
    var returned = result.getFirst();
    assertThat(returned.getId()).isEqualTo(existing.getId());
    assertThat(returned.getName()).isEqualTo("New Name");
    assertThat(personRepository.database).hasSize(1);
  }

  @Test
  @DisplayName("Should handle multiple persons with mix of new and existing")
  void shouldHandleMultiplePersonsWithMixOfNewAndExisting() {
    var existing =
        personRepository.save(
            Person.builder().name("Existing Actor").sourceId("existing-1").build());

    var updatedExisting = Person.builder().name("Updated Actor").sourceId("existing-1").build();
    var brandNew = Person.builder().name("Brand New Actor").sourceId("new-1").build();

    var result = personService.getOrCreatePersons(List.of(updatedExisting, brandNew), Map.of());

    assertThat(result).hasSize(2);
    assertThat(personRepository.database).hasSize(2);

    var returnedExisting =
        result.stream().filter(p -> "existing-1".equals(p.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedExisting.getId()).isEqualTo(existing.getId());
    assertThat(returnedExisting.getName()).isEqualTo("Updated Actor");

    var returnedNew =
        result.stream().filter(p -> "new-1".equals(p.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedNew.getName()).isEqualTo("Brand New Actor");
    assertThat(returnedNew.getId()).isNotNull();
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating new person with image sources")
  void shouldPublishMetadataEnrichedEventWhenCreatingNewPersonWithImageSources() {
    var person = Person.builder().name("Tom Hanks").sourceId("actor-1").build();
    var imageSources =
        Map.<String, List<ImageSource>>of(
            "actor-1", List.of(new TmdbImageSource(ImageType.PROFILE, "/tom.jpg")));

    personService.getOrCreatePersons(List.of(person), imageSources);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityType()).isEqualTo(ImageEntityType.PERSON);
  }

  @Test
  @DisplayName("Should not publish event when person already exists")
  void shouldNotPublishEventWhenPersonAlreadyExists() {
    personRepository.save(Person.builder().name("Tom Hanks").sourceId("actor-1").build());

    var updated = Person.builder().name("Tom Hanks").sourceId("actor-1").build();
    var imageSources =
        Map.<String, List<ImageSource>>of(
            "actor-1", List.of(new TmdbImageSource(ImageType.PROFILE, "/tom.jpg")));

    personService.getOrCreatePersons(List.of(updated), imageSources);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).isEmpty();
  }
}
