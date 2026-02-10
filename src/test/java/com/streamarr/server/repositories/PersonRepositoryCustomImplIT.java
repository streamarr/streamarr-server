package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.AuditorAware;

@Tag("IntegrationTest")
@DisplayName("PersonRepositoryCustomImpl Integration Tests")
public class PersonRepositoryCustomImplIT extends AbstractIntegrationTest {

  @Autowired private PersonRepository personRepository;

  @Autowired private AuditorAware<UUID> auditorAware;

  @Test
  @DisplayName("Should populate audit fields from AuditorAware when inserting via jOOQ")
  void shouldPopulateAuditFieldsFromAuditorAwareWhenInsertingViaJooq() {
    var sourceId = "audit-test-" + UUID.randomUUID();
    var inserted = personRepository.insertOnConflictDoNothing(sourceId, "Audit Test Person");

    assertThat(inserted).isTrue();

    var person = personRepository.findPersonBySourceId(sourceId).orElseThrow();
    var expectedAuditor = auditorAware.getCurrentAuditor().orElseThrow();

    assertThat(person.getCreatedBy()).isEqualTo(expectedAuditor);
    assertThat(person.getLastModifiedBy()).isEqualTo(expectedAuditor);
    assertThat(person.getCreatedOn()).isNotNull();
    assertThat(person.getLastModifiedOn()).isNotNull();
  }

  @Test
  @DisplayName("Should return false without inserting when conflict on source ID and name")
  void shouldReturnFalseWithoutInsertingWhenConflictOnSourceIdAndName() {
    var sourceId = "conflict-test-" + UUID.randomUUID();
    var firstInsert = personRepository.insertOnConflictDoNothing(sourceId, "Same Name");

    assertThat(firstInsert).isTrue();

    var secondInsert = personRepository.insertOnConflictDoNothing(sourceId, "Same Name");

    assertThat(secondInsert).isFalse();
    assertThat(personRepository.findPersonBySourceId(sourceId)).isPresent();
  }
}
