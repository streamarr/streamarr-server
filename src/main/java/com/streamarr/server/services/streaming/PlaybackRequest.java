package com.streamarr.server.services.streaming;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record PlaybackRequest(
    @NonNull UUID streamSessionId, @NonNull AuthenticatedIdentity identity) {}
