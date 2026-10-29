package com.streamarr.server.services.streaming.events;

import com.streamarr.server.domain.streaming.TranscodeFailure;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A job attempt failed in a way that a replacement attempt would repeat, and no other format
 * attempt follows it. A copy's output mismatch publishes {@link VideoCopyOutputMismatchEvent}
 * instead, because Auto falls back to an encode.
 */
@Builder
public record TerminalTranscodeFailureEvent(
    @NonNull UUID streamSessionId,
    @NonNull UUID jobAttemptId,
    @NonNull String variantLabel,
    @NonNull TranscodeFailure failure) {}
