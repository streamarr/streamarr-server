package com.streamarr.server.domain.auth;

import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/** Audit data for one successful administrative transition; never include secrets. */
@Builder
public record SecurityAuditEntry(
    @NonNull String operation,
    @NonNull UUID actorAccountId,
    String reason,
    @Singular Map<String, UUID> resources) {}
