package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ChangedManagerInvitation;
import com.streamarr.server.domain.auth.ManagerInvitationAction;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.events.auth.ProfileManagerInvitationChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Every write of a ProfileManager invitation (ADR 0024 §Invitations). The workflows that invite,
 * decide, cancel and invalidate call it inside their own transactions; no other class writes an
 * invitation. Each invitation a write changes is announced as a {@link
 * ProfileManagerInvitationChangedEvent}, and a write that changes nothing announces nothing. The
 * database ends an invitation whose party is deleted, so whoever deletes a party ends that party's
 * invitations here first.
 */
@Service
@RequiredArgsConstructor
public class ProfileManagerInvitationLifecycle {

  private final ProfileManagerInvitationRepository invitationRepository;
  private final ApplicationEventPublisher events;

  /** Stores a new pending invitation and announces it. */
  public ProfileManagerInvitation issue(ProfileManagerInvitation invitation) {
    var issued = invitationRepository.saveAndFlush(invitation);
    events.publishEvent(
        new ProfileManagerInvitationChangedEvent(
            ChangedManagerInvitation.of(issued), new ManagerInvitationAction.Issued()));
    return issued;
  }

  /**
   * The manager who sent the invitation, or a ServerAdmin, cancels it while it is live and pending;
   * false when it was not.
   */
  public boolean tryCancelPending(UUID invitationId, UUID cancelingAccountId, Instant now) {
    return announced(
        invitationRepository.tryCancelPending(invitationId, now),
        new ManagerInvitationAction.Canceled(cancelingAccountId));
  }

  /** The named Account accepts a live pending invitation; false when it was not. */
  public boolean tryAcceptPending(UUID invitationId, UUID acceptingAccountId, Instant now) {
    return announced(
        invitationRepository.tryAcceptPending(invitationId, now),
        new ManagerInvitationAction.Answered(acceptingAccountId));
  }

  /** The named Account declines a live pending invitation; false when it was not. */
  public boolean tryDeclinePending(UUID invitationId, UUID decliningAccountId, Instant now) {
    return announced(
        invitationRepository.tryDeclinePending(invitationId, now),
        new ManagerInvitationAction.Answered(decliningAccountId));
  }

  /*
   * Each withdrawal below also stores EXPIRED on the pending invitations in its scope whose time
   * ran out, and announces those as stopped waiting with the rest.
   */

  /** Withdraws one pending invitation with the reason. */
  public void tryInvalidatePending(UUID invitationId, String reason, Instant now) {
    announceStoppedWaiting(invitationRepository.tryInvalidatePending(invitationId, reason, now));
  }

  /** Withdraws the pending invitation to manage the Profile sent to the address, in any case. */
  public void invalidatePendingByProfileIdAndRecipientEmail(
      UUID profileId, String email, String reason, Instant now) {
    announceStoppedWaiting(
        invitationRepository.invalidatePendingByProfileIdAndRecipientEmail(
            profileId, email, reason, now));
  }

  /** Withdraws the pending invitation to manage the Profile that names the Account. */
  public void invalidatePendingByProfileIdAndRecipientAccountId(
      UUID profileId, UUID recipientAccountId, String reason, Instant now) {
    announceStoppedWaiting(
        invitationRepository.invalidatePendingByProfileIdAndRecipientAccountId(
            profileId, recipientAccountId, reason, now));
  }

  /** Withdraws every pending invitation to manage the Profile. */
  public void invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    announceStoppedWaiting(
        invitationRepository.invalidatePendingByProfileId(profileId, reason, now));
  }

  /** Withdraws the pending invitations to manage the Profile that the Account sent. */
  public void invalidatePendingInvitationsByInviterAccountIdAndProfileId(
      UUID inviterAccountId, UUID profileId, String reason, Instant now) {
    announceStoppedWaiting(
        invitationRepository.invalidatePendingInvitationsByInviterAccountIdAndProfileId(
            inviterAccountId, profileId, reason, now));
  }

  /** Withdraws every pending invitation the Account sent. */
  public void invalidatePendingInvitedBy(UUID inviterAccountId, String reason, Instant now) {
    announceStoppedWaiting(
        invitationRepository.invalidatePendingInvitedBy(inviterAccountId, reason, now));
  }

  /** Withdraws every pending invitation that names the Account. */
  public void invalidatePendingByRecipientAccountId(
      UUID recipientAccountId, String reason, Instant now) {
    announceStoppedWaiting(
        invitationRepository.invalidatePendingByRecipientAccountId(
            recipientAccountId, reason, now));
  }

  /** Stores EXPIRED on every pending invitation whose time ran out; answers how many. */
  public int expireLapsed(Instant now) {
    var expired = invitationRepository.materializeExpiredPending(now);
    announceStoppedWaiting(expired);
    return expired.size();
  }

  private boolean announced(
      Optional<ChangedManagerInvitation> changed, ManagerInvitationAction action) {
    changed.ifPresent(
        invitation ->
            events.publishEvent(new ProfileManagerInvitationChangedEvent(invitation, action)));
    return changed.isPresent();
  }

  private void announceStoppedWaiting(List<ChangedManagerInvitation> invitations) {
    invitations.forEach(
        invitation ->
            events.publishEvent(
                new ProfileManagerInvitationChangedEvent(
                    invitation, new ManagerInvitationAction.StoppedWaiting())));
  }
}
