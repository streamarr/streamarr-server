package com.streamarr.server.config;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "streaming.distributed")
public record DistributedTranscodeProperties(
    boolean enabled, String caSecretPath, Duration signingLease, Duration bootstrapTimeout) {

  private static final Duration MAXIMUM_SIGNING_LEASE = Duration.ofMinutes(5);
  private static final Duration MAXIMUM_BOOTSTRAP_TIMEOUT = Duration.ofMinutes(10);

  public DistributedTranscodeProperties {
    if (caSecretPath == null) {
      caSecretPath = "";
    }
    if (signingLease == null) {
      signingLease = Duration.ofSeconds(30);
    }
    if (bootstrapTimeout == null) {
      bootstrapTimeout = Duration.ofMinutes(2);
    }

    if (enabled) {
      validateOperationalDurations(signingLease, bootstrapTimeout);
    }
  }

  private static void validateOperationalDurations(
      Duration signingLease, Duration bootstrapTimeout) {
    if (signingLease.isZero() || signingLease.isNegative()) {
      throw new IllegalArgumentException("Certificate authority signing lease must be positive");
    }
    if (signingLease.getNano() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Certificate authority signing lease must use microsecond precision");
    }
    if (bootstrapTimeout.isZero() || bootstrapTimeout.isNegative()) {
      throw new IllegalArgumentException("Installation trust bootstrap timeout must be positive");
    }
    if (signingLease.compareTo(MAXIMUM_SIGNING_LEASE) > 0) {
      throw new IllegalArgumentException(
          "Certificate authority signing lease must not exceed 5 minutes");
    }
    if (bootstrapTimeout.compareTo(MAXIMUM_BOOTSTRAP_TIMEOUT) > 0) {
      throw new IllegalArgumentException(
          "Installation trust bootstrap timeout must not exceed 10 minutes");
    }
  }

  public Path requiredCaSecretPath() {
    if (caSecretPath.isBlank()) {
      throw new IllegalArgumentException("Distributed transcoding requires a CA secret path");
    }
    var path = Path.of(caSecretPath);
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(
          "Distributed transcoding requires an absolute CA secret path");
    }
    return path;
  }
}
