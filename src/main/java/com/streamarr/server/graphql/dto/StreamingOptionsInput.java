package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.language.Language;

public record StreamingOptionsInput(
    String quality,
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrate,
    Language audioLanguage,
    Language subtitleLanguage,
    String dynamicRange) {}
