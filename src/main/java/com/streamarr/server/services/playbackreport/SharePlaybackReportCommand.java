package com.streamarr.server.services.playbackreport;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code streamSessionId} names the format attempt whose figures the player captured. A blank note
 * counts as none.
 */
@Builder
public record SharePlaybackReportCommand(
    @NonNull AuthenticatedIdentity identity,
    @NonNull UUID playbackSessionId,
    @NonNull UUID streamSessionId,
    @NonNull Optional<String> note,
    @NonNull PlayerFigures player) {}
