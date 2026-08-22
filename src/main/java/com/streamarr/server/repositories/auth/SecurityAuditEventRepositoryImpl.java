package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SecurityAuditEventRepositoryImpl implements SecurityAuditEventRepository {

  private static final String SUCCESS = "SUCCESS";

  private final DSLContext dsl;

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

  /** Keys are code-owned identifiers and values are UUIDs, so the JSON needs no escaping. */
  private static JSONB resourcesJson(Map<String, UUID> resources) {
    return JSONB.jsonb(
        resources.entrySet().stream()
            .map(entry -> "\"%s\": \"%s\"".formatted(entry.getKey(), entry.getValue()))
            .collect(Collectors.joining(", ", "{", "}")));
  }
}
