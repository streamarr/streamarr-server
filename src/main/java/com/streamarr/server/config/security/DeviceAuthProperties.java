package com.streamarr.server.config.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

@Builder
@Validated
@ConfigurationProperties(prefix = "auth.device")
public record DeviceAuthProperties(
    // Returned to clients as expiresIn; they drive their own countdown from the response.
    @NotNull @DurationMin(minutes = 1) @DurationMax(minutes = 30) Duration codeTtl,
    // The initial enforced and returned interval; slow_down raises it by five seconds a time.
    @Min(5) @Max(300) int pollIntervalSeconds,
    // Absolute-path form, joined beneath the canonical base URL.
    @NotBlank String verificationPath,
    // The hard issuance limit: a global count of outstanding pending codes.
    @Positive int maxOutstandingCodes,
    @NotNull @DurationMin(seconds = 1) @DurationUnit(ChronoUnit.MILLIS) Duration sweepInterval) {

  public DeviceAuthProperties {
    if (verificationPath != null) {
      requireAbsolutePath(verificationPath);
    }
  }

  private static void requireAbsolutePath(String verificationPath) {
    if (!verificationPath.startsWith("/")) {
      throw new IllegalStateException(
          "auth.device.verification-path (%s) must be an absolute path."
              .formatted(verificationPath));
    }
    if (verificationPath.contains("?") || verificationPath.contains("#")) {
      throw new IllegalStateException(
          "auth.device.verification-path (%s) must not carry a query or fragment."
              .formatted(verificationPath));
    }
    if (verificationPath.contains("..")) {
      throw new IllegalStateException(
          "auth.device.verification-path (%s) must not contain dot segments."
              .formatted(verificationPath));
    }
  }
}
