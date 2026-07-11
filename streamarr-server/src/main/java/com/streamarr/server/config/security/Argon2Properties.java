package com.streamarr.server.config.security;

import jakarta.validation.constraints.Positive;
import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "auth.argon2")
public record Argon2Properties(
    @Positive int memoryKib, @Positive int iterations, @Positive int parallelism) {}
