package com.streamarr.server.services.identity;

import com.streamarr.server.services.auth.ProfileManagerInvitationLifecycle;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Ends what a manager proposed for one Profile once it stops managing it, however it stops (ADR
 * 0024 §Invitations): the invitations it sent to manage the Profile and the offers it made of it.
 * Runs inside the caller's transaction.
 */
@Component
@RequiredArgsConstructor
class LeavingManagerProposals {

  static final String INVITER_LEFT_REASON = "inviting manager lost management";

  private final ProfileManagerInvitationLifecycle invitationLifecycle;
  private final ProfileShareLifecycle shareLifecycle;

  void withdraw(UUID profileId, UUID leaverAccountId, Instant now) {
    invitationLifecycle.invalidatePendingInvitationsByInviterAccountIdAndProfileId(
        leaverAccountId, profileId, INVITER_LEFT_REASON, now);
    shareLifecycle.invalidatePendingOffersByProfileIdAndOffererAccountId(
        profileId, leaverAccountId, "offering manager lost management", now);
  }
}
