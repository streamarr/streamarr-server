package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record PlaybackRequest(
    @NonNull UUID streamSessionId, @NonNull PlaybackAuthority authority) {}
