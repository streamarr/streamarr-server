package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
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
    variantLabel = variantLabel != null ? variantLabel : StreamSession.defaultVariant();
    attemptId = attemptId != null ? attemptId : UUID.randomUUID();
  }
}
