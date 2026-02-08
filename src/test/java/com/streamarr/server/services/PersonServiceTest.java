package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakePersonRepository;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
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
    var person =
        Person.builder().name("Tom Hanks").sourceId("actor-1").profilePath("/tom.jpg").build();

    var result = personService.getOrCreatePersons(List.of(person));

    assertThat(result).hasSize(1);
    var saved = result.getFirst();
    assertThat(saved.getName()).isEqualTo("Tom Hanks");
    assertThat(saved.getSourceId()).isEqualTo("actor-1");
    assertThat(saved.getProfilePath()).isEqualTo("/tom.jpg");
    assertThat(saved.getId()).isNotNull();
    assertThat(personRepository.database).hasSize(1);
  }

  @Test
  @DisplayName("Should update existing person when source ID already exists")
  void shouldUpdateExistingPersonWhenSourceIdAlreadyExists() {
    var existing =
        personRepository.save(
            Person.builder().name("Old Name").sourceId("actor-1").profilePath("/old.jpg").build());

    var updated =
        Person.builder().name("New Name").sourceId("actor-1").profilePath("/new.jpg").build();

    var result = personService.getOrCreatePersons(List.of(updated));

    assertThat(result).hasSize(1);
    var returned = result.getFirst();
    assertThat(returned.getId()).isEqualTo(existing.getId());
    assertThat(returned.getName()).isEqualTo("New Name");
    assertThat(returned.getProfilePath()).isEqualTo("/new.jpg");
    assertThat(personRepository.database).hasSize(1);
  }

  @Test
  @DisplayName("Should handle multiple persons with mix of new and existing")
  void shouldHandleMultiplePersonsWithMixOfNewAndExisting() {
    var existing =
        personRepository.save(
            Person.builder()
                .name("Existing Actor")
                .sourceId("existing-1")
                .profilePath("/existing.jpg")
                .build());

    var updatedExisting =
        Person.builder()
            .name("Updated Actor")
            .sourceId("existing-1")
            .profilePath("/updated.jpg")
            .build();
    var brandNew =
        Person.builder()
            .name("Brand New Actor")
            .sourceId("new-1")
            .profilePath("/brand-new.jpg")
            .build();

    var result = personService.getOrCreatePersons(List.of(updatedExisting, brandNew));

    assertThat(result).hasSize(2);
    assertThat(personRepository.database).hasSize(2);

    var returnedExisting =
        result.stream().filter(p -> "existing-1".equals(p.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedExisting.getId()).isEqualTo(existing.getId());
    assertThat(returnedExisting.getName()).isEqualTo("Updated Actor");
    assertThat(returnedExisting.getProfilePath()).isEqualTo("/updated.jpg");

    var returnedNew =
        result.stream().filter(p -> "new-1".equals(p.getSourceId())).findFirst().orElseThrow();
    assertThat(returnedNew.getName()).isEqualTo("Brand New Actor");
    assertThat(returnedNew.getProfilePath()).isEqualTo("/brand-new.jpg");
    assertThat(returnedNew.getId()).isNotNull();
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating new person")
  void shouldPublishMetadataEnrichedEventWhenCreatingNewPerson() {
    var person =
        Person.builder().name("Tom Hanks").sourceId("actor-1").profilePath("/tom.jpg").build();

    personService.getOrCreatePersons(List.of(person));

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityType()).isEqualTo(ImageEntityType.PERSON);
  }

  @Test
  @DisplayName("Should not publish event when person already exists")
  void shouldNotPublishEventWhenPersonAlreadyExists() {
    personRepository.save(
        Person.builder().name("Tom Hanks").sourceId("actor-1").profilePath("/tom.jpg").build());

    var updated =
        Person.builder().name("Tom Hanks").sourceId("actor-1").profilePath("/updated.jpg").build();

    personService.getOrCreatePersons(List.of(updated));

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).isEmpty();
  }
}
