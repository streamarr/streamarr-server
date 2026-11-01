package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
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

  @BeforeEach
  void resetAuditEvents() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
  }

  @Test
  @DisplayName("Should preserve resource keys requiring JSON escaping when an entry is appended")
  void shouldPreserveResourceKeysRequiringJsonEscapingWhenEntryAppended() throws Exception {
    var actorAccountId = UUID.randomUUID();
    var resourceId = UUID.randomUUID();
    repository.append(
        SecurityAuditEntry.builder()
            .operation("testOperation")
            .actorAccountId(actorAccountId)
            .resource("quoted\"key", resourceId)
            .build());

    var resources =
        dsl.select(SECURITY_AUDIT_EVENT.RESOURCES)
            .from(SECURITY_AUDIT_EVENT)
            .where(SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID.eq(actorAccountId))
            .fetchSingle(SECURITY_AUDIT_EVENT.RESOURCES)
            .data();

    assertThat(objectMapper.readTree(resources).path("quoted\"key").asString())
        .isEqualTo(resourceId.toString());
  }
}
