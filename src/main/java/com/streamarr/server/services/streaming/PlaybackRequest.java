package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record PlaybackRequest(UUID streamSessionId, PlaybackAuthority authority) {

  public PlaybackRequest {
    Objects.requireNonNull(streamSessionId, "streamSessionId is required");
    Objects.requireNonNull(authority, "authority is required");
  }
}
