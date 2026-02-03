package com.streamarr.server.domain.streaming;

import java.util.List;
import lombok.Builder;

@Builder
public record StreamingOptions(
    VideoQuality quality,
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrate,
    List<String> supportedCodecs,
    String audioLanguage,
    String subtitleLanguage) {}
