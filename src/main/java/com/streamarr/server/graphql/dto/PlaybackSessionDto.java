package com.streamarr.server.graphql.dto;

import lombok.Builder;

@Builder
public record PlaybackSessionDto(
    String id,
    int formatAttemptCount,
    int maximumFormatAttempts,
    PlaybackFormatAttemptDto currentFormatAttempt) {}
