package com.streamarr.server.domain.auth;

import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/**
 * One security-sensitive administrative win: actor, operation, affected resources, and any required
 * reason. Only the winning transition is recorded, never secrets or hidden resource data.
 */
@Builder
public record SecurityAuditEntry(
    @NonNull String operation,
    @NonNull UUID actorAccountId,
    String reason,
    @Singular Map<String, UUID> resources) {}
