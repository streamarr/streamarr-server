package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record CreateStreamSessionCommand(
    @NonNull UUID mediaFileId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamingOptions options) {}
