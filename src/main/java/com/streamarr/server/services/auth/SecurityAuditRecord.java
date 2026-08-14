package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.SecurityAuditOperation;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record SecurityAuditRecord(
    @NonNull UUID actingAccountId,
    UUID targetAccountId,
    UUID targetHouseholdId,
    UUID targetProfileId,
    @NonNull SecurityAuditOperation operation,
    String reason) {}
