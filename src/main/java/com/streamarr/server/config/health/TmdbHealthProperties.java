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
    // The probe must not inherit the enrichment budget the tmdb client carries (15s to connect,
    // 30s per request): client.send is synchronous, so that budget is time a request thread spends
    // parked on a third party. Ten seconds is the ceiling because Spring Boot itself starts warning
    // about slow health contributors there.
    @NotNull @DurationMin(seconds = 0, inclusive = false) @DurationMax(seconds = 10)
        Duration probeTimeout,
    // A probe that calls a third party on every hit is an amplifier: an uptime monitor polling
    // every 10s becomes six TMDB requests a minute, forever. Serving a cached verdict keeps the
    // signal without the traffic.
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration cacheTtl) {}
