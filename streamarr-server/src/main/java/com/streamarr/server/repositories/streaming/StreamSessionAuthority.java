package com.streamarr.server.repositories.streaming;

import java.util.UUID;
import lombok.Builder;

@Builder
public record StreamSessionAuthority(
    UUID streamSessionId,
    UUID authSessionId,
    UUID accountId,
    UUID householdId,
    UUID profileId,
    UUID mediaFileId) {}
