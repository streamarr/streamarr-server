package com.streamarr.server.graphql.dto;

import java.util.List;

public record StreamingOptionsInput(
    String quality,
    Integer maxWidth,
    Integer maxHeight,
    Integer maxBitrate,
    List<String> supportedCodecs,
    List<String> supportedAudioCodecs,
    Integer maxAudioChannels,
    String audioLanguage,
    String subtitleLanguage) {}
