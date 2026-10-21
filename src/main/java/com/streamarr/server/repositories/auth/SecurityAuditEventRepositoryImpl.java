package com.streamarr.server.repositories.auth;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;

import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.jooq.generated.tables.records.SecurityAuditEventRecord;
import com.streamarr.server.repositories.Keyset;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.time.OffsetDateTime;
import java.util.List;
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

  @Override
  public List<Row<SecurityAuditEventRecordView>> rowsBeyond(Seek seek) {
    var keyset =
        Keyset.<OffsetDateTime>builder()
            .sortValue(SECURITY_AUDIT_EVENT.OCCURRED_AT)
            .itemId(SECURITY_AUDIT_EVENT.ID)
            .build();
    return keyset
        .rowsBeyond(dsl.selectFrom(SECURITY_AUDIT_EVENT), seek)
        .fetch(row -> new Row<>(view(row), keyset.rowOf(row).position()));
  }

  private static SecurityAuditEventRecordView view(SecurityAuditEventRecord row) {
    return new SecurityAuditEventRecordView(
        row.getId(),
        row.getOccurredAt().toInstant(),
        row.getActorAccountId(),
        row.getOperation(),
        row.getOutcome(),
        row.getReason(),
        row.getResources().data());
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
