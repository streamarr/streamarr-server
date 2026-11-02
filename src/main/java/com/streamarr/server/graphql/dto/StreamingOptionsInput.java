package com.streamarr.server.graphql.dto;

public record StreamingOptionsInput(
    String quality,
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrate,
    String audioLanguage,
    String subtitleLanguage) {}
