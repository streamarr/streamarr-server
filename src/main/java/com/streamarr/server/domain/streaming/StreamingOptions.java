package com.streamarr.server.domain.streaming;

import lombok.Builder;

/** The viewer's playback choices. What the client can play is its capability declaration. */
@Builder
public record StreamingOptions(
    VideoQuality quality,
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrate,
    String audioLanguage,
    String subtitleLanguage) {}
