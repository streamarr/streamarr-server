package com.streamarr.server.config.security;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Builder;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Opaque one-time credentials (ADR 0024 §Invitations, §Account): how long an invitation and a
 * password-reset code stay redeemable, and how long issuance waits for the replacement lock
 * (advisory lock on the recipient email, Account row locks) before giving up. Defaults live in
 * application.yml.
 */
@Builder
@Validated
@ConfigurationProperties(prefix = "auth.credential-codes")
public record CredentialCodeProperties(
    @NotNull @DurationMin(minutes = 5) Duration invitationTtl,
    @NotNull @DurationMin(minutes = 5) Duration passwordResetTtl,
    @NotNull @DurationMin(millis = 1) Duration replacementLockTimeout) {}
