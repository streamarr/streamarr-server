package com.streamarr.server.domain.auth;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;
import lombok.Singular;

/**
 * One security-sensitive administrative win: actor, operation, affected resources, and any required
 * reason. Only the winning transition is recorded, never secrets or hidden resource data.
 */
@Builder
public record SecurityAuditEntry(
    String operation, UUID actorAccountId, String reason, @Singular Map<String, UUID> resources) {

  public SecurityAuditEntry {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(actorAccountId, "actorAccountId");
  }
}
