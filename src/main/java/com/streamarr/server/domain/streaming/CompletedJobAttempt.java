package com.streamarr.server.domain.streaming;

import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A job attempt that read its source to the end. {@code sourceEndedEarly} is the worker's report
 * that the source ran out before the last advertised media segment.
 */
@Builder
public record CompletedJobAttempt(
    @NonNull UUID streamSessionId,
    @NonNull UUID jobAttemptId,
    @NonNull String variantLabel,
    @NonNull Optional<SourceEndedEarly> sourceEndedEarly) {}
