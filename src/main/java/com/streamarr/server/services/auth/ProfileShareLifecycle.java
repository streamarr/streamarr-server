package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.auth.events.ProfileShareChangedEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Every write of a Profile share (ADR 0024 §Profile sharing). The workflows that offer, decide,
 * end, link, move and delete call it inside their own transactions; no other class writes a share.
 * Each share a write changes is announced as a {@link ProfileShareChangedEvent}, and a write that
 * changes nothing announces nothing. The database deletes a share with its Profile or its
 * Household, so whoever deletes either announces those shares here first.
 */
@Service
@RequiredArgsConstructor
public class ProfileShareLifecycle {

  /**
   * Why the system withdrew an offer whose offerer may no longer make it. The target Household's
   * HouseholdAdmins read it, so it says nothing about the offerer's Account, such as whether that
   * Account was disabled or held ServerAdmin.
   */
  public static final String OFFERER_NO_LONGER_AUTHORIZED = "offerer no longer authorized";

  private final ProfileHouseholdShareRepository shareRepository;
  private final ApplicationEventPublisher events;

  /** Stores a new share. */
  public ProfileHouseholdShare create(ProfileHouseholdShare share) {
    var created = shareRepository.saveAndFlush(share);
    announce(ChangedShare.of(created));
    return created;
  }

  /** Withdraws the Profile's pending offer to the Household so a new one can be offered. */
  public void supersedePending(UUID profileId, UUID householdId, Instant now) {
    announce(shareRepository.supersedePending(profileId, householdId, now));
  }

  /** Withdraws one pending offer with the reason it will later explain; false when not pending. */
  public boolean tryInvalidatePending(UUID shareId, String reason, Instant now) {
    return announced(shareRepository.tryInvalidatePending(shareId, reason, now));
  }

  /** Withdraws every unexpired pending offer the Account made; answers how many. */
  public int invalidatePendingOfferedBy(UUID offererAccountId, String reason, Instant now) {
    var withdrawn = shareRepository.invalidatePendingOfferedBy(offererAccountId, reason, now);
    announce(withdrawn);
    return withdrawn.size();
  }

  /** Stores EXPIRED on every offer whose time ran out; answers how many. */
  public int expireLapsedOffers(Instant now) {
    var expired = shareRepository.materializeExpiredPending(now);
    announce(expired);
    return expired.size();
  }

  /** Accepts a pending, unexpired offer; false when another decision won. */
  public boolean tryActivatePending(UUID shareId, Instant now) {
    return announced(shareRepository.tryActivatePending(shareId, now));
  }

  /** Declines or cancels a pending offer; false when it was no longer pending. */
  public boolean tryDeclinePending(UUID shareId, ProfileShareStatus target, Instant now) {
    return announced(shareRepository.tryDeclinePending(shareId, target, now));
  }

  /** Ends an active share; false when it was not active. */
  public boolean tryEndActive(UUID shareId, Instant now) {
    return announced(shareRepository.tryEndActive(shareId, now));
  }

  /** Makes the Profile's availability in its Account's Household the structural share. */
  public void ensureActiveMembershipShare(UUID profileId, UUID householdId, Instant now) {
    announce(shareRepository.ensureActiveMembershipShare(profileId, householdId, now));
  }

  /** Withdraws every pending offer of the Profile with the reason. */
  public void invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    announce(shareRepository.invalidatePendingByProfileId(profileId, reason, now));
  }

  /** Turns the Profile's structural share into the Household into an ordinary visitor share. */
  public void convertMembershipShareToVisitorShare(UUID profileId, UUID householdId) {
    announce(shareRepository.convertMembershipShareToVisitorShare(profileId, householdId));
  }

  /** Withdraws the leaving manager's own pending offers of the Profile. */
  public void invalidatePendingOffersByProfileIdAndOffererAccountId(
      UUID profileId, UUID offererAccountId, String reason, Instant now) {
    announce(
        shareRepository.invalidatePendingOffersByProfileIdAndOffererAccountId(
            profileId, offererAccountId, reason, now));
  }

  /** Announces every share of the Profile, which the database deletes with the Profile. */
  public void announceDeletionWithProfile(UUID profileId) {
    shareRepository.findByProfileId(profileId).forEach(share -> announce(ChangedShare.of(share)));
  }

  /** Announces every share into the Household, which the database deletes with the Household. */
  public void announceDeletionWithHousehold(UUID householdId) {
    shareRepository
        .findByHouseholdId(householdId)
        .forEach(share -> announce(ChangedShare.of(share)));
  }

  private boolean announced(Optional<ChangedShare> changed) {
    changed.ifPresent(this::announce);
    return changed.isPresent();
  }

  private void announce(List<ChangedShare> changed) {
    changed.forEach(this::announce);
  }

  private void announce(ChangedShare changed) {
    events.publishEvent(new ProfileShareChangedEvent(changed));
  }
}
