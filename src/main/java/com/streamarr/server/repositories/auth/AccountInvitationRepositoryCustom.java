package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import java.time.Instant;
import java.util.List;
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

  boolean markCanceledIfPendingAndUnexpired(UUID invitationId, Instant now);

  /** Invalidates the PENDING invitation for the email, ignoring case (replacement rule). */
  int invalidatePendingInvitationsForRecipientEmail(
      String recipientEmail, String reason, Instant now);

  /** Invalidates every PENDING invitation the issuer left behind (disable, revocation). */
  int invalidatePendingInvitationsIssuedBy(UUID issuerAccountId, String reason, Instant now);

  int expirePendingInvitationsForRecipientEmail(String recipientEmail, Instant now);
}
