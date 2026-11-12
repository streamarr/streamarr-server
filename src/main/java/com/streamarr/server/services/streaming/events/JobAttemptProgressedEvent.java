package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A worker reports that a running job attempt advanced: its producer read more of FFmpeg's output,
 * its search for the video's last keyframe read more of the source, or its FFmpeg read more of the
 * source while a copy's audio input read on and FFmpeg wrote nothing (ADR 0037).
 */
@Builder
public record JobAttemptProgressedEvent(
    @NonNull UUID streamSessionId, @NonNull UUID jobAttemptId, @NonNull String variantLabel) {}
