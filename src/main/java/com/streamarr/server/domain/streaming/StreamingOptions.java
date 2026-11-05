package com.streamarr.server.domain.streaming;

import com.streamarr.server.domain.language.Language;
import lombok.Builder;

/**
 * The viewer's playback choices. What the client can play is its capability declaration. {@code
 * audioLanguage} wins over every stored language preference for this session (ADR 0043).
 */
@Builder(toBuilder = true)
public record StreamingOptions(
    VideoQuality quality,
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrate,
    Language audioLanguage,
    Language subtitleLanguage) {}
