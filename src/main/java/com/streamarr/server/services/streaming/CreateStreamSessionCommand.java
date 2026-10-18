package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** The caller reserves the session identifier before a worker can report a startup failure. */
@Builder
public record CreateStreamSessionCommand(
    @NonNull UUID streamSessionId,
    @NonNull UUID mediaFileId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamingOptions options) {}
