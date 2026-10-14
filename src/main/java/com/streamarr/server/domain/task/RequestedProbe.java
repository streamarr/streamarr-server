package com.streamarr.server.domain.task;

import java.time.Instant;
import lombok.NonNull;

/** The inputs a scan or file discovery requested a probe with, and when it requested them. */
public record RequestedProbe(@NonNull ProbeInputs inputs, @NonNull Instant requestedAt) {}
