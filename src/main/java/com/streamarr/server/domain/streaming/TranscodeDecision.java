package com.streamarr.server.domain.streaming;

import lombok.Builder;

@Builder
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    AudioDecision audioDecision,
    ContainerFormat containerFormat,
    boolean needsKeyframeAlignment) {}
