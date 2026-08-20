package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SecurityAuditEventView(
    UUID id,
    String occurredAt,
    UUID actorAccountId,
    String operation,
    String outcome,
    String reason,
    String resources) {

  public static SecurityAuditEventView from(SecurityAuditEventRecordView record) {
    return SecurityAuditEventView.builder()
        .id(record.id())
        .occurredAt(record.occurredAt().toString())
        .actorAccountId(record.actorAccountId())
        .operation(record.operation())
        .outcome(record.outcome())
        .reason(record.reason())
        .resources(record.resources())
        .build();
  }

  /** The keyset cursor key: strictly descending (occurredAt, id). */
  public String cursorKey() {
    return occurredAt + "|" + id;
  }
}
