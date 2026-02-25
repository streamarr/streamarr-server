package com.streamarr.server.domain.streaming;

import lombok.Builder;

@Builder
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    AudioDecision audioDecision,
    SubtitleDecision subtitleDecision,
    ContainerFormat containerFormat,
    boolean needsKeyframeAlignment) {}
