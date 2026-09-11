package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProbeExecutionRequest(
    @NonNull Path sourcePath, @NonNull UUID attemptId, int probeVersion) {}
