package com.streamarr.server.repositories.streaming;

import java.util.UUID;
import lombok.Builder;

@Builder
public record PlaybackRequestAuthority(
    UUID streamSessionId, UUID authSessionId, UUID accountId, UUID householdId, UUID profileId) {}
