package com.streamarr.server.graphql.dto;

import lombok.Builder;

@Builder
public record WatchProgressDto(int positionSeconds, double percentComplete, int durationSeconds) {}
