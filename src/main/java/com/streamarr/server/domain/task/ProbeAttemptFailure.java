package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.ItemFailureReason;
import java.time.Instant;
import lombok.Builder;
import lombok.NonNull;

/**
 * Why the latest attempt at a media file's requested probe inputs failed. The probe keeps retrying,
 * so this is not a terminal probe error.
 */
@Builder
public record ProbeAttemptFailure(
    @NonNull ItemFailureReason reason, @NonNull String detail, @NonNull Instant failedAt) {}
