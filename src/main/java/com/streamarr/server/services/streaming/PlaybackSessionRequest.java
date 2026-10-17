package com.streamarr.server.services.streaming;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record PlaybackSessionRequest(
    @NonNull UUID playbackSessionId, @NonNull AuthenticatedIdentity identity) {}
