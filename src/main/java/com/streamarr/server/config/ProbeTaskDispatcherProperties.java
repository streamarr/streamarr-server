package com.streamarr.server.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "task.probe")
@Validated
public record ProbeTaskDispatcherProperties(@DefaultValue("2") @Min(1) int maxConcurrent) {}
