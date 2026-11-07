package com.streamarr.server.services.playbackreport;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * {@code streamSessionId} names the format attempt whose figures the player captured. The client
 * makes a new {@code submissionId} for each press of Share and sends it again when it resends. A
 * blank note counts as none.
 */
@Builder(toBuilder = true)
public record SharePlaybackReportCommand(
    @NonNull AuthenticatedIdentity identity,
    @NonNull UUID playbackSessionId,
    @NonNull UUID streamSessionId,
    @NonNull UUID submissionId,
    @NonNull Optional<String> note,
    @NonNull PlayerFigures player) {}
