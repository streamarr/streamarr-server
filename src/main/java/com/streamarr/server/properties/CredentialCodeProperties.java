package com.streamarr.server.properties;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Opaque one-time credentials (ADR 0024 §Invitations, §Account, §Share codes): how long an
 * invitation, a password-reset code and a share code stay usable, and how long issuance waits for
 * the replacement lock (advisory lock on the recipient email, Account row locks) before giving up.
 * Defaults live in application.yml.
 */
@Builder
@Validated
@ConfigurationProperties(prefix = "auth.credential-codes")
public record CredentialCodeProperties(
    @NotNull @DurationMin(minutes = 5) Duration invitationTtl,
    @NotNull @DurationMin(minutes = 5) Duration passwordResetTtl,
    // At most 30 days, so a mistaken setting cannot leave a code open to guessing for months.
    @NotNull @DurationMin(minutes = 5) @DurationMax(days = 30) Duration shareCodeTtl,
    @NotNull @DurationMin(millis = 1) Duration replacementLockTimeout) {}
