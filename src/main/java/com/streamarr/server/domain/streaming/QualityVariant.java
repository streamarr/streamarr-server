package com.streamarr.server.domain.streaming;

import lombok.Builder;

@Builder
public record QualityVariant(int width, int height, long videoBitrate, long audioBitrate, String label) {}
