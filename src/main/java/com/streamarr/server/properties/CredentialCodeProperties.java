package com.streamarr.server.properties;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Opaque one-time credentials (ADR 0024 §Invitations, §Account, §Share codes): how long an
 * invitation, a password-reset code and a share code stay usable, the key share codes are
 * recognised by, and how long issuance waits for the replacement lock (advisory lock on the
 * recipient email, Account row locks) before giving up. Defaults live in application.yml.
 */
@Builder
@Validated
@ConfigurationProperties(prefix = "auth.credential-codes")
public record CredentialCodeProperties(
    @NotNull @DurationMin(minutes = 5) Duration invitationTtl,
    @NotNull @DurationMin(minutes = 5) Duration passwordResetTtl,
    @NotNull @DurationMin(minutes = 5) Duration shareCodeTtl,
    // Base64; blank makes a key for the run, so live share codes stop working on restart.
    String shareCodeKey,
    @NotNull @DurationMin(millis = 1) Duration replacementLockTimeout) {

  public static class CredentialCodePropertiesBuilder {

    @Override
    public String toString() {
      return ("CredentialCodePropertiesBuilder[invitationTtl=%s, passwordResetTtl=%s,"
              + " shareCodeTtl=%s, shareCodeKey=[REDACTED], replacementLockTimeout=%s]")
          .formatted(invitationTtl, passwordResetTtl, shareCodeTtl, replacementLockTimeout);
    }
  }

  @Override
  public String toString() {
    return ("CredentialCodeProperties[invitationTtl=%s, passwordResetTtl=%s, shareCodeTtl=%s,"
            + " shareCodeKey=[REDACTED], replacementLockTimeout=%s]")
        .formatted(invitationTtl, passwordResetTtl, shareCodeTtl, replacementLockTimeout);
  }
}
