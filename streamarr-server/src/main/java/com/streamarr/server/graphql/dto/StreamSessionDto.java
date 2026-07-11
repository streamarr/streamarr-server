package com.streamarr.server.graphql.dto;

import lombok.Builder;

@Builder
public record StreamSessionDto(String id, String streamUrl, String transcodeMode) {}
