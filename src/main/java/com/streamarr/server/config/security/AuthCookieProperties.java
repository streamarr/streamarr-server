package com.streamarr.server.config.security;

import lombok.Builder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Opt-in to auth cookies without the {@code Secure} attribute. The flag alone unlocks nothing: it
 * is honored only alongside a development or test profile.
 */
@Builder
@Validated
@ConfigurationProperties(prefix = "auth.cookies")
public record AuthCookieProperties(boolean allowInsecure) {}
