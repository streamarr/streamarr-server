package com.streamarr.transcode.engine.model;

import lombok.Builder;

@Builder
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    AudioDecision audioDecision,
    SubtitleDecision subtitleDecision,
    ContainerFormat containerFormat,
    boolean needsKeyframeAlignment) {

  public TranscodeDecision {
    if (transcodeMode == null
        || videoCodecFamily == null
        || videoCodecFamily.isBlank()
        || audioDecision == null
        || subtitleDecision == null
        || containerFormat == null) {
      throw new IllegalArgumentException("Transcode decision values are required");
    }
  }
}
