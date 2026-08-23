package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Repository
@RequiredArgsConstructor
public class SecurityAuditEventRepositoryImpl implements SecurityAuditEventRepository {

  private static final String SUCCESS = "SUCCESS";

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  @Override
  public void append(SecurityAuditEntry entry) {
    dsl.insertInto(SECURITY_AUDIT_EVENT)
        .set(SECURITY_AUDIT_EVENT.ACTOR_ACCOUNT_ID, entry.actorAccountId())
        .set(SECURITY_AUDIT_EVENT.OPERATION, entry.operation())
        .set(SECURITY_AUDIT_EVENT.OUTCOME, SUCCESS)
        .set(SECURITY_AUDIT_EVENT.REASON, entry.reason())
        .set(SECURITY_AUDIT_EVENT.RESOURCES, resourcesJson(entry.resources()))
        .execute();
  }

  private JSONB resourcesJson(Map<String, UUID> resources) {
    try {
      return JSONB.jsonb(objectMapper.writeValueAsString(resources));
    } catch (JacksonException exception) {
      throw new IllegalStateException(
          "Security audit resources could not be serialized", exception);
    }
  }
}
