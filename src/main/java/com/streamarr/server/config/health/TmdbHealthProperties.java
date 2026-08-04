package com.streamarr.server.config.health;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "tmdb.health")
public record TmdbHealthProperties(
    // The dedicated health client applies this to both connection establishment and the request.
    // Ten seconds is the ceiling because Spring Boot warns about slow health contributors there.
    @NotNull @DurationMin(seconds = 0, inclusive = false) @DurationMax(seconds = 10)
        Duration probeTimeout,
    // A probe that calls a third party on every hit is an amplifier: an uptime monitor polling
    // every 10s becomes six TMDB requests a minute, forever. Serving a cached verdict keeps the
    // signal without the traffic. One day is the ceiling because older results are not useful as
    // health signals and an unbounded duration can overflow expiry calculation.
    @NotNull @DurationMin(seconds = 0, inclusive = false) @DurationMax(days = 1)
        Duration cacheTtl) {}
