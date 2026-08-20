package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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

  @Override
  public List<SecurityAuditEventRecordView> pageNewestFirst(
      Instant beforeOccurredAt, UUID beforeId, int limit) {
    var query = dsl.selectFrom(SECURITY_AUDIT_EVENT);
    var page =
        beforeOccurredAt == null
            ? query
            : query.where(
                SECURITY_AUDIT_EVENT
                    .OCCURRED_AT
                    .lt(beforeOccurredAt.atOffset(ZoneOffset.UTC))
                    .or(
                        SECURITY_AUDIT_EVENT
                            .OCCURRED_AT
                            .eq(beforeOccurredAt.atOffset(ZoneOffset.UTC))
                            .and(SECURITY_AUDIT_EVENT.ID.lt(beforeId))));
    return page.orderBy(SECURITY_AUDIT_EVENT.OCCURRED_AT.desc(), SECURITY_AUDIT_EVENT.ID.desc())
        .limit(limit)
        .fetch(
            record ->
                new SecurityAuditEventRecordView(
                    record.getId(),
                    record.getOccurredAt().toInstant(),
                    record.getActorAccountId(),
                    record.getOperation(),
                    record.getOutcome(),
                    record.getReason(),
                    record.getResources().data()));
  }

  /** Keys are code-owned identifiers and values are UUIDs, so the JSON needs no escaping. */
  private static JSONB resourcesJson(Map<String, UUID> resources) {
    return JSONB.jsonb(
        resources.entrySet().stream()
            .map(entry -> "\"%s\": \"%s\"".formatted(entry.getKey(), entry.getValue()))
            .collect(Collectors.joining(", ", "{", "}")));
  }
}
