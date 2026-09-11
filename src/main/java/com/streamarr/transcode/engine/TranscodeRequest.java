package com.streamarr.transcode.engine;

import java.nio.file.Path;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeRequest(
    @NonNull UUID sessionId,
    UUID attemptId,
    @NonNull Path sourcePath,
    int seekPosition,
    int targetSegmentDuration,
    double framerate,
    @NonNull TranscodeDecision transcodeDecision,
    int width,
    int height,
    long bitrate,
    String variantLabel,
    int startSequenceNumber) {

  public TranscodeRequest {
    if (variantLabel == null) {
      variantLabel = "default";
    }

    if (attemptId == null) {
      attemptId = UUID.randomUUID();
    }
  }
}
