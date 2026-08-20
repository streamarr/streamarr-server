package com.streamarr.server.config.security;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Lifetimes of the opaque one-time codes (ADR 0024 §Invitations). */
@Validated
@ConfigurationProperties(prefix = "auth.credential-codes")
public record CredentialCodeProperties(
    @NotNull @DurationMin(minutes = 5) Duration invitationTtl,
    @NotNull @DurationMin(minutes = 5) Duration passwordResetTtl) {

  private static final Duration DEFAULT_INVITATION_TTL = Duration.ofDays(7);
  private static final Duration DEFAULT_PASSWORD_RESET_TTL = Duration.ofHours(1);

  public CredentialCodeProperties {
    if (invitationTtl == null) {
      invitationTtl = DEFAULT_INVITATION_TTL;
    }
    if (passwordResetTtl == null) {
      passwordResetTtl = DEFAULT_PASSWORD_RESET_TTL;
    }
  }
}
