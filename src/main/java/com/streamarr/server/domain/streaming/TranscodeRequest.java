package com.streamarr.server.domain.streaming;

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
      variantLabel = StreamSession.defaultVariant();
    }

    // The attempt id is minted only here. Dispatched jobs, the TranscodeHandle, and
    // stop/replace fencing all carry it verbatim.
    if (attemptId == null) {
      attemptId = UUID.randomUUID();
    }
  }
}
