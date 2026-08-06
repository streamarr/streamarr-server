package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.PlaybackAuthority;
import com.streamarr.server.domain.streaming.StreamingOptions;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record CreateStreamSessionCommand(
    UUID mediaFileId, PlaybackAuthority authority, StreamingOptions options) {

  public CreateStreamSessionCommand {
    Objects.requireNonNull(mediaFileId, "mediaFileId is required");
    Objects.requireNonNull(authority, "authority is required");
    Objects.requireNonNull(options, "options are required");
  }
}
