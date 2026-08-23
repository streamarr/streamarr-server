package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Security Audit Event Repository Integration Tests")
class SecurityAuditEventRepositoryIT extends AbstractIntegrationTest {

  @Autowired private SecurityAuditEventRepository repository;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;

  @AfterEach
  void deleteAuditEvents() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
  }

  @Test
  @DisplayName("Should preserve resource keys requiring JSON escaping when an entry is appended")
  void shouldPreserveResourceKeysRequiringJsonEscapingWhenEntryAppended() throws Exception {
    var resourceId = UUID.randomUUID();
    repository.append(
        SecurityAuditEntry.builder()
            .operation("testOperation")
            .actorAccountId(UUID.randomUUID())
            .resource("quoted\"key", resourceId)
            .build());

    var resources =
        dsl.select(SECURITY_AUDIT_EVENT.RESOURCES)
            .from(SECURITY_AUDIT_EVENT)
            .fetchSingle(SECURITY_AUDIT_EVENT.RESOURCES)
            .data();

    assertThat(objectMapper.readTree(resources).path("quoted\"key").asString())
        .isEqualTo(resourceId.toString());
  }
}
