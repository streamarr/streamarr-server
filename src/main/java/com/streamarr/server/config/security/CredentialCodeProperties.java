package com.streamarr.server.config.security;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/** Configuration for opaque one-time credentials (ADR 0024 §Invitations). */
@Validated
@ConfigurationProperties(prefix = "auth.credential-codes")
public record CredentialCodeProperties(
    @NotNull @DurationMin(minutes = 5) Duration invitationTtl,
    @NotNull @DurationMin(minutes = 5) Duration passwordResetTtl,
    @NotNull @DurationMin(millis = 1) Duration replacementLockTimeout) {

  private static final Duration DEFAULT_INVITATION_TTL = Duration.ofDays(7);
  private static final Duration DEFAULT_PASSWORD_RESET_TTL = Duration.ofHours(1);
  private static final Duration DEFAULT_REPLACEMENT_LOCK_TIMEOUT = Duration.ofSeconds(5);

  public CredentialCodeProperties(Duration invitationTtl, Duration passwordResetTtl) {
    this(invitationTtl, passwordResetTtl, DEFAULT_REPLACEMENT_LOCK_TIMEOUT);
  }

  @ConstructorBinding
  public CredentialCodeProperties {
    if (invitationTtl == null) {
      invitationTtl = DEFAULT_INVITATION_TTL;
    }

    if (passwordResetTtl == null) {
      passwordResetTtl = DEFAULT_PASSWORD_RESET_TTL;
    }

    if (replacementLockTimeout == null) {
      replacementLockTimeout = DEFAULT_REPLACEMENT_LOCK_TIMEOUT;
    }
  }
}
