package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SecurityAuditOperation;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record SecurityAuditRecord(
    UUID actingAccountId,
    UUID targetAccountId,
    UUID targetHouseholdId,
    UUID targetProfileId,
    SecurityAuditOperation operation,
    String reason) {

  public SecurityAuditRecord {
    Objects.requireNonNull(actingAccountId, "actingAccountId");
    Objects.requireNonNull(operation, "operation");
  }
}
