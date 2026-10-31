package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.ClientCapabilityDeclaration;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** Every format attempt of the playback session replays the options and capability declaration. */
@Builder
public record StartPlaybackSessionCommand(
    @NonNull UUID mediaFileId,
    @NonNull AuthenticatedIdentity identity,
    @NonNull StreamingOptions options,
    @NonNull ClientCapabilityDeclaration capabilityDeclaration) {}
