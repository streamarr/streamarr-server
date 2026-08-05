package com.streamarr.server.config.health;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configures bounded, cached TMDB reachability probes.
 *
 * @param probeTimeout maximum duration for connection establishment and the request
 * @param cacheTtl duration that a completed probe result remains fresh
 */
@Builder
@Validated
@ConfigurationProperties(prefix = "tmdb.health")
public record TmdbHealthProperties(
    @NotNull @DurationMin(seconds = 0, inclusive = false) @DurationMax(seconds = 10)
        Duration probeTimeout,
    @NotNull @DurationMin(seconds = 0, inclusive = false) @DurationMax(days = 1)
        Duration cacheTtl) {}
