package com.streamarr.server.repositories.streaming;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record StreamSessionCompletion(
    @NonNull UUID streamSessionId,
    @NonNull UUID profileId,
    @NonNull UUID collectableId,
    int durationSeconds) {}
