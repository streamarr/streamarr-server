package com.streamarr.server.config.security;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "auth.token")
public record AuthTokenProperties(
    String signingKey,
    List<String> verificationKeys,
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration accessTokenTtl,
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration refreshTokenTtl,
    @NotNull @DurationMin Duration rotationGrace) {}
