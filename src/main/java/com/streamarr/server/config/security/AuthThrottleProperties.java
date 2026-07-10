package com.streamarr.server.config.security;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "auth.throttle")
public record AuthThrottleProperties(
    @Positive int maxAttempts,
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration window) {}
