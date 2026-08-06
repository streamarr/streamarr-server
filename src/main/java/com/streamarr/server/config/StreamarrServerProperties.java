package com.streamarr.server.config;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The externally reachable identity of this server. Nothing else in the application knows it: a
 * container sees only its internal address, so every URL a client must be able to reach is built
 * from this value or not emitted at all.
 */
@Builder
@Validated
@ConfigurationProperties(prefix = "streamarr")
public record StreamarrServerProperties(String baseUrl, boolean allowInsecureHttp) {}
