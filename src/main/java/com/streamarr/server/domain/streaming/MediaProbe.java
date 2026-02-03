package com.streamarr.server.domain.streaming;

import java.time.Duration;
import lombok.Builder;

@Builder
public record MediaProbe(
    Duration duration,
    double framerate,
    int width,
    int height,
    String videoCodec,
    String audioCodec,
    long bitrate) {}
