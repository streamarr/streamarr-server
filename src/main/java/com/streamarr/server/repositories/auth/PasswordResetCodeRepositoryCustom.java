package com.streamarr.server.repositories.auth;

import java.time.Instant;
import java.util.UUID;

public interface PasswordResetCodeRepositoryCustom {

  /** Conditionally redeems one PENDING, unexpired code; a raced redemption has one winner. */
  boolean markRedeemedIfPendingAndUnexpired(UUID codeId, Instant now);

  /** Invalidates the Account's PENDING code (issuing a new one replaces the old). */
  int invalidatePendingPasswordResetCodesForAccount(UUID accountId, String reason, Instant now);

  /** Invalidates every PENDING code the issuer left behind (disable, revocation). */
  int invalidatePendingPasswordResetCodesIssuedBy(UUID issuerAccountId, String reason, Instant now);

  int expirePendingPasswordResetCodesForAccount(UUID accountId, Instant now);
}
