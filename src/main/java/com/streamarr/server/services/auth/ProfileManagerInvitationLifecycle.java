package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Every write of a ProfileManager invitation (ADR 0024 §Invitations). The workflows that invite,
 * decide, cancel and invalidate call it inside their own transactions; no other class writes an
 * invitation. The database ends an invitation whose party is deleted, so whoever deletes a party
 * ends that party's invitations here first.
 */
@Service
@RequiredArgsConstructor
public class ProfileManagerInvitationLifecycle {

  private final ProfileManagerInvitationRepository invitationRepository;

  /** Stores a new pending invitation. */
  public ProfileManagerInvitation issue(ProfileManagerInvitation invitation) {
    return invitationRepository.saveAndFlush(invitation);
  }

  /** Cancels a live pending invitation; false when it was not live and pending. */
  public boolean tryCancelPending(UUID invitationId, Instant now) {
    return invitationRepository.tryCancelPending(invitationId, now).isPresent();
  }

  /** The named Account accepts a live pending invitation; false when it was not. */
  public boolean tryAcceptPending(UUID invitationId, Instant now) {
    return invitationRepository.tryAcceptPending(invitationId, now).isPresent();
  }

  /** The named Account declines a live pending invitation; false when it was not. */
  public boolean tryDeclinePending(UUID invitationId, Instant now) {
    return invitationRepository.tryDeclinePending(invitationId, now).isPresent();
  }

  /** Withdraws one pending invitation with the reason. */
  public void tryInvalidatePending(UUID invitationId, String reason, Instant now) {
    invitationRepository.tryInvalidatePending(invitationId, reason, now);
  }

  /** Withdraws the pending invitation to manage the Profile that names the Account. */
  public void invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    invitationRepository.invalidatePendingByProfileIdAndRecipientAccountId(
        profileId, recipientAccountId, reason, now);
  }

  /** Withdraws every pending invitation to manage the Profile. */
  public void invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    invitationRepository.invalidatePendingByProfileId(profileId, reason, now);
  }

  /** Withdraws the pending invitations to manage the Profile that the Account sent. */
  public void invalidatePendingInvitationsByInviterAccountIdAndProfileId(
      UUID inviterAccountId, UUID profileId, String reason, Instant now) {
    invitationRepository.invalidatePendingInvitationsByInviterAccountIdAndProfileId(
        inviterAccountId, profileId, reason, now);
  }

  /** Withdraws every pending invitation the Account sent. */
  public void invalidatePendingInvitedBy(UUID inviterAccountId, String reason, Instant now) {
    invitationRepository.invalidatePendingInvitedBy(inviterAccountId, reason, now);
  }

  /** Withdraws every pending invitation that names the Account. */
  public void invalidatePendingByRecipientAccountId(
      UUID recipientAccountId, String reason, Instant now) {
    invitationRepository.invalidatePendingByRecipientAccountId(recipientAccountId, reason, now);
  }
}
