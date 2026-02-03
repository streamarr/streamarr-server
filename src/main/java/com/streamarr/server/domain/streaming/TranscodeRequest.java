package com.streamarr.server.domain.streaming;

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
    long bitrate) {}
