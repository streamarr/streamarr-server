package com.streamarr.server.repositories.auth;

import java.time.Instant;
import java.util.UUID;

public interface PasswordResetCodeRepositoryCustom {

  /** Conditionally redeems one PENDING, unexpired code; a raced redemption has one winner. */
  boolean tryRedeem(UUID codeId, Instant now);

  /** Invalidates the Account's PENDING code (issuing a new one replaces the old). */
  int invalidatePendingForAccount(UUID accountId, String reason, Instant now);

  /** Invalidates every PENDING code the issuer left behind (disable, revocation). */
  int invalidateIssuedBy(UUID issuerAccountId, String reason, Instant now);

  /** Marks PENDING codes whose expiry passed as EXPIRED, for reporting. */
  int sweepExpired(Instant now);
}
