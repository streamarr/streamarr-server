package com.streamarr.transcode.engine.model;

import java.util.UUID;

public record TranscodeJobRef(UUID jobId, long generation) {

  public TranscodeJobRef {
    if (jobId == null) {
      throw new IllegalArgumentException("Transcode job identity is required");
    }
    if (generation < 1) {
      throw new IllegalArgumentException("Transcode generation must be positive");
    }
  }
}
