package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record CreateStreamSessionCommand(
    @NonNull UUID mediaFileId,
    @NonNull PlaybackAuthority authority,
    @NonNull StreamingOptions options) {}
