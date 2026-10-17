package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

public record VideoCopyOutputMismatchEvent(
    @NonNull UUID streamSessionId, @NonNull UUID jobAttemptId, @NonNull String variantLabel) {}
