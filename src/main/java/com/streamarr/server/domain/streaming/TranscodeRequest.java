package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TranscodeRequest(
    UUID sessionId,
    UUID attemptId,
    Path sourcePath,
    int seekPosition,
    int targetSegmentDuration,
    double framerate,
    TranscodeDecision transcodeDecision,
    int width,
    int height,
    long bitrate,
    String variantLabel,
    int startSequenceNumber) {

  public TranscodeRequest {
    Objects.requireNonNull(sessionId, "sessionId is required");
    Objects.requireNonNull(sourcePath, "sourcePath is required");
    Objects.requireNonNull(transcodeDecision, "transcodeDecision is required");
    variantLabel = variantLabel != null ? variantLabel : StreamSession.defaultVariant();
    attemptId = attemptId != null ? attemptId : UUID.randomUUID();
  }
}
