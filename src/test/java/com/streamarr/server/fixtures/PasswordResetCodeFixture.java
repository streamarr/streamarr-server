package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.PasswordResetCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class PasswordResetCodeFixture {

  private PasswordResetCodeFixture() {}

  /** A pending, unexpired reset code; the subject and issuer Accounts are the caller's. */
  public static PasswordResetCode.PasswordResetCodeBuilder<?, ?> pendingResetCodeBuilder() {
    return PasswordResetCode.builder()
        .expiresAt(Instant.now().plus(Duration.ofDays(1)))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[32]);
  }
}
