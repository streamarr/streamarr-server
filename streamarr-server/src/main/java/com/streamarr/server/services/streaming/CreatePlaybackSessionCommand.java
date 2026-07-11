package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CreatePlaybackSessionCommand(
    UUID mediaFileId, StreamingOptions options, AuthenticatedIdentity sourceIdentity) {}
