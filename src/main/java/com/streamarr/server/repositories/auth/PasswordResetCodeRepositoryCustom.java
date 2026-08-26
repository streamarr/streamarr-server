package com.streamarr.server.repositories.auth;

import java.time.Instant;
import java.util.UUID;

public interface PasswordResetCodeRepositoryCustom {

  /** Conditionally redeems one PENDING, unexpired code; a raced redemption has one winner. */
  boolean markRedeemedIfPendingAndUnexpired(UUID codeId, Instant now);

  /**
   * Invalidates the Account's PENDING, unexpired code (issuing a new one replaces the old); an
   * expired one is materialized as EXPIRED first, see below.
   */
  int invalidatePendingPasswordResetCodesForAccount(UUID accountId, String reason, Instant now);

  /**
   * Invalidates every PENDING, unexpired code the issuer left behind (disable, revocation); expired
   * ones keep projecting EXPIRED.
   */
  int invalidatePendingPasswordResetCodesIssuedBy(UUID issuerAccountId, String reason, Instant now);

  /**
   * Materializes EXPIRED for the Account's stale PENDING code so the one-PENDING-per-Account index
   * slot is free for a replacement. Expiry is otherwise a read-time projection.
   */
  int expirePendingPasswordResetCodesForAccount(UUID accountId, Instant now);
}
