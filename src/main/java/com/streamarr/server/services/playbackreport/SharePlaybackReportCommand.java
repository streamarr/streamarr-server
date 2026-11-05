package com.streamarr.server.services.playbackreport;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** A blank note counts as no note. */
@Builder
public record SharePlaybackReportCommand(
    @NonNull AuthenticatedIdentity identity,
    @NonNull UUID playbackSessionId,
    @NonNull Optional<String> note,
    @NonNull PlayerFigures player) {}
