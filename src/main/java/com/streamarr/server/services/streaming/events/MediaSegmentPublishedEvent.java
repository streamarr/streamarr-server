package com.streamarr.server.services.streaming.events;

import com.streamarr.server.services.streaming.ExecutionTargetId;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A job attempt's upload from {@code executionTarget} stored the variant's media segment at {@code
 * segmentIndex}. Media stored by an earlier job attempt, or by an upload that a disconnected worker
 * began, counts as another job attempt's output.
 */
@Builder
public record MediaSegmentPublishedEvent(
    @NonNull UUID streamSessionId,
    @NonNull UUID jobAttemptId,
    @NonNull String variantLabel,
    int segmentIndex,
    @NonNull ExecutionTargetId executionTarget) {}
