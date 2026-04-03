package com.streamarr.server.graphql.dto;

import java.time.Instant;
import lombok.Builder;

@Builder
public record WatchProgressDto(
    int positionSeconds, double percentComplete, int durationSeconds, Instant lastModifiedOn) {}
