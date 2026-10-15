package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.security.TestIdentityConstants;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("PersonRepositoryCustomImpl Integration Tests")
@WithProfileContext
class PersonRepositoryCustomImplIT extends AbstractIntegrationTest {

  @Autowired private PersonRepository personRepository;

  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("Should name the signed-in Account in the audit columns when inserting via jOOQ")
  void shouldNameTheSignedInAccountInTheAuditColumnsWhenInsertingViaJooq() {
    var sourceId = "audit-test-" + UUID.randomUUID();

    var inserted = insertIfAbsent(sourceId, "Audit Test Person");

    assertThat(inserted).isTrue();
    var person = personRepository.findPersonBySourceId(sourceId).orElseThrow();
    assertThat(person.getCreatedBy()).isEqualTo(TestIdentityConstants.ACCOUNT_ID);
    assertThat(person.getLastModifiedBy()).isEqualTo(TestIdentityConstants.ACCOUNT_ID);
    assertThat(person.getCreatedOn()).isNotNull();
    assertThat(person.getLastModifiedOn()).isNotNull();
  }

  @Test
  @DisplayName("Should return false without inserting when source ID already exists")
  void shouldReturnFalseWithoutInsertingWhenSourceIdAlreadyExists() {
    var sourceId = "conflict-test-" + UUID.randomUUID();
    var firstInsert = insertIfAbsent(sourceId, "Original Name");

    assertThat(firstInsert).isTrue();

    var secondInsert = insertIfAbsent(sourceId, "Different Name");

    assertThat(secondInsert).isFalse();
    var person = personRepository.findPersonBySourceId(sourceId).orElseThrow();
    assertThat(person.getName()).isEqualTo("Original Name");
  }

  private boolean insertIfAbsent(String sourceId, String name) {
    return Boolean.TRUE.equals(
        transactionTemplate.execute(_ -> personRepository.insertIfAbsent(sourceId, name)));
  }
}
