package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

@Tag("IntegrationTest")
@DisplayName("Person Service Transaction Integration Tests")
class PersonServiceTransactionIT extends AbstractIntegrationTest {

  @Autowired private PersonService personService;

  @Autowired private PersonRepository personRepository;

  @Test
  @DisplayName("Should roll back earlier inserts when a later person violates a constraint")
  void shouldRollBackEarlierInsertsWhenLaterPersonViolatesConstraint() {
    var suffix = UUID.randomUUID().toString();
    var validSourceId = "100-valid-" + suffix;
    var invalidSourceId = "200-invalid-" + suffix;
    var persons =
        List.of(
            Person.builder().name("Valid Actor").sourceId(validSourceId).build(),
            Person.builder().name(null).sourceId(invalidSourceId).build());

    assertThatThrownBy(() -> personService.getOrCreatePersons(persons, Map.of()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(personRepository.findPersonBySourceId(validSourceId)).isEmpty();
    assertThat(personRepository.findPersonBySourceId(invalidSourceId)).isEmpty();
  }
}
