package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SecurityAuditEventDetails(
    UUID id,
    String occurredAt,
    UUID actorAccountId,
    String operation,
    String outcome,
    String reason,
    String resources) {

  public static SecurityAuditEventDetails from(SecurityAuditEventRecordView row) {
    return SecurityAuditEventDetails.builder()
        .id(row.id())
        .occurredAt(row.occurredAt().toString())
        .actorAccountId(row.actorAccountId())
        .operation(row.operation())
        .outcome(row.outcome())
        .reason(row.reason())
        .resources(row.resources())
        .build();
  }
}
