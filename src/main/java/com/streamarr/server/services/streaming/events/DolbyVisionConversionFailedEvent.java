package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.NonNull;

/**
 * A copy that converts single-track Dolby Vision profile 7 to profile 8.1 failed on a picture whose
 * RPU the worker cannot convert, which every attempt of the same copy repeats
 * (streamarr/streamarr-server#651). The playback session moves to the copy with Dolby Vision
 * stripped (ADR 0052).
 */
public record DolbyVisionConversionFailedEvent(
    @NonNull UUID streamSessionId, @NonNull UUID jobAttemptId, @NonNull String variantLabel) {}
