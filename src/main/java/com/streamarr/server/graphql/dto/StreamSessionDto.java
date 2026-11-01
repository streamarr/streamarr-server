package com.streamarr.server.graphql.dto;

import lombok.Builder;

/** {@code mediaEndSeconds} is null while nothing shows that the media ends before its playlist. */
@Builder
public record StreamSessionDto(
    String id, String streamUrl, String transcodeMode, String videoRange, Double mediaEndSeconds) {}
