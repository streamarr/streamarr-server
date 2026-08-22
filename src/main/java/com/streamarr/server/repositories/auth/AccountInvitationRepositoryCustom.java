package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountInvitationStatus;
import java.time.Instant;
import java.util.UUID;

public interface AccountInvitationRepositoryCustom {

  /** Serializes replacement for one case-insensitive recipient across application instances. */
  void lockRecipientForReplacement(String recipientEmail);

  /**
   * Conditionally moves one PENDING, unexpired invitation to a terminal status. True means this
   * statement made the transition — a raced acceptance, decline, or cancel has exactly one winner.
   */
  boolean tryDecide(UUID invitationId, AccountInvitationStatus target, Instant now);

  /** Invalidates the PENDING invitation for the email, ignoring case (replacement rule). */
  int invalidatePendingForEmail(String recipientEmail, String reason, Instant now);

  /** Invalidates every PENDING invitation bound to the Profile (connected, moved, or deleted). */
  int invalidatePendingForProfile(UUID profileId, String reason, Instant now);

  /** Invalidates every PENDING invitation the issuer left behind (disable, revocation). */
  int invalidateIssuedBy(UUID issuerAccountId, String reason, Instant now);

  /** Marks PENDING invitations whose expiry passed as EXPIRED, for reporting. */
  int sweepExpired(Instant now);
}
