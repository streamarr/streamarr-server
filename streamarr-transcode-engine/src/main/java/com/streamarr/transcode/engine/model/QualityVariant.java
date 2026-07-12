package com.streamarr.transcode.engine.model;

import lombok.Builder;

@Builder
public record QualityVariant(
    int width, int height, long videoBitrate, long audioBitrate, String label) {}
