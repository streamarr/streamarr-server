package com.streamarr.transcode.engine.model;

import java.nio.file.Path;
import java.util.UUID;
import lombok.Builder;

@Builder
public record TranscodeRequest(
    UUID sessionId,
    Path sourcePath,
    int seekPosition,
    int segmentDuration,
    double framerate,
    TranscodeDecision transcodeDecision,
    int width,
    int height,
    long bitrate,
    String variantLabel,
    int startNumber) {

  public static final String DEFAULT_VARIANT = "default";

  public TranscodeRequest {
    variantLabel = variantLabel != null ? variantLabel : DEFAULT_VARIANT;
  }
}
