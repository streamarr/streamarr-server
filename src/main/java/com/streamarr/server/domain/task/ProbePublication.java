package com.streamarr.server.domain.task;

import com.streamarr.server.domain.media.SourceFileSnapshot;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A probe result together with the source snapshot and probe version it was produced for. The
 * repository writes it only while no newer result exists for the same snapshot.
 */
@Builder
public record ProbePublication(
    @NonNull UUID mediaFileId,
    @NonNull SourceFileSnapshot snapshot,
    int probeVersion,
    @NonNull ProbeOutcome outcome) {}
