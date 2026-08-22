package com.streamarr.server.domain.streaming;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record PlaybackAuthority(
    @NonNull UUID authSessionId,
    @NonNull UUID accountId,
    @NonNull UUID householdId,
    @NonNull UUID profileId) {}
