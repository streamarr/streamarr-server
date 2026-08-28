package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountInvitationRepositoryCustom {

  List<AccountInvitation> findAdministrationPage(MediaPaginationOptions options);

  /** Serializes replacement for one case-insensitive recipient across application instances. */
  void lockInvitationIssuanceForRecipientEmail(String recipientEmail);

  /**
   * Conditionally moves one PENDING, unexpired invitation to a terminal status. True means this
   * statement made the transition — a raced acceptance, decline, or cancel has exactly one winner.
   */
  boolean markAcceptedIfPendingAndUnexpired(UUID invitationId, Instant now);

  boolean markDeclinedIfPendingAndUnexpired(UUID invitationId, Instant now);

  /**
   * Cancels one PENDING, unexpired invitation and returns it as it now stands, read past
   * Hibernate's first-level cache; empty when nothing was pending.
   */
  Optional<AccountInvitation> cancelIfPendingAndUnexpired(UUID invitationId, Instant now);

  /** Invalidates every PENDING invitation bound to the Profile (linked, moved, or deleted). */
  int invalidatePendingByProfileId(UUID profileId, String reason, Instant now);

  /**
   * Invalidates the PENDING, unexpired invitation for the email, ignoring case (replacement rule);
   * an expired one is materialized as EXPIRED first, see below.
   */
  int invalidatePendingInvitationsForRecipientEmail(
      String recipientEmail, String reason, Instant now);

  /**
   * Invalidates every PENDING, unexpired invitation the issuer left behind (disable, revocation);
   * expired ones keep projecting EXPIRED.
   */
  int invalidatePendingInvitationsIssuedBy(UUID issuerAccountId, String reason, Instant now);

  /**
   * Materializes EXPIRED for the recipient's stale PENDING invitation, recording when, so the
   * one-PENDING-per-email index slot is free for a replacement. Expiry is otherwise a read-time
   * projection.
   */
  int expirePendingInvitationsForRecipientEmail(String recipientEmail, Instant now);
}
