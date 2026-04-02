package com.streamarr.server.graphql.dto;

import java.util.Optional;
import lombok.Builder;

@Builder
public record WatchProgressDto(
    int positionSeconds,
    double percentComplete,
    int durationSeconds,
    Optional<String> lastPlayedAt) {}
