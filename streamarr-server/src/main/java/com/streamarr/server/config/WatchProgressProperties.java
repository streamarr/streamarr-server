package com.streamarr.server.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "streaming.watch-progress")
public record WatchProgressProperties(
    @Min(0) double minPlayedPercent,
    @Positive double maxPlayedPercent,
    @Min(0) int maxRemainingSeconds) {}
