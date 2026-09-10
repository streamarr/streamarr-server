package com.streamarr.server.domain.task;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProbeClaim(
    @NonNull UUID taskId,
    @NonNull UUID claimId,
    @NonNull ProbeRequest request,
    @NonNull Instant leaseExpiresAt,
    int retryCount) {}
