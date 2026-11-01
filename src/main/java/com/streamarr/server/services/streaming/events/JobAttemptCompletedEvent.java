package com.streamarr.server.services.streaming.events;

import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A worker reports that a job attempt read its source to the end. The worker reports the completion
 * only after the server accepted the attempt's last segment.
 */
@Builder
public record JobAttemptCompletedEvent(
    @NonNull UUID streamSessionId, @NonNull UUID jobAttemptId, @NonNull String variantLabel) {}
