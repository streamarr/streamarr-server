package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record ProbePublication(
    @NonNull ProbeClaim claim,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion,
    @NonNull ProbeOutcome outcome) {}
