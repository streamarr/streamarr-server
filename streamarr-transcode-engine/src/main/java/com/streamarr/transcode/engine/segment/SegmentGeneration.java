package com.streamarr.transcode.engine.segment;

import com.streamarr.transcode.engine.model.TranscodeJobRef;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

public final class SegmentGeneration {

  private final UUID sessionId;
  private final TranscodeJobRef jobRef;
  private final Path outputDirectory;

  SegmentGeneration(UUID sessionId, TranscodeJobRef jobRef, Path outputDirectory) {
    this.sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
    this.jobRef = Objects.requireNonNull(jobRef, "jobRef must not be null");
    this.outputDirectory =
        Objects.requireNonNull(outputDirectory, "outputDirectory must not be null");
  }

  public UUID sessionId() {
    return sessionId;
  }

  public TranscodeJobRef jobRef() {
    return jobRef;
  }

  public Path outputDirectory() {
    return outputDirectory;
  }
}
