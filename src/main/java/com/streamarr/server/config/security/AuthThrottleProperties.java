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
    @NotNull @DurationMin(seconds = 0, inclusive = false) Duration window,
    @NotNull @Positive Integer maxOpaqueCodeBudgets) {

  public static final int DEFAULT_MAX_OPAQUE_CODE_BUDGETS = 10_000;

  public AuthThrottleProperties {
    if (maxOpaqueCodeBudgets == null) {
      maxOpaqueCodeBudgets = DEFAULT_MAX_OPAQUE_CODE_BUDGETS;
    }
  }
}
