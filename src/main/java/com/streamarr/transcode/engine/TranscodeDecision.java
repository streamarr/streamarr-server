package com.streamarr.transcode.engine;

import lombok.Builder;

@Builder
public record TranscodeDecision(
    TranscodeMode transcodeMode,
    String videoCodecFamily,
    AudioDecision audioDecision,
    SubtitleDecision subtitleDecision,
    ContainerFormat containerFormat,
    boolean needsKeyframeAlignment) {}
