package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareAction;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
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
 *
 * <p>Every write changes a share that is pending or active, so each is announced as the change of a
 * live share, the change that ends it included. A share announced without being written, as it is
 * moved or deleted, is live when it is stored pending or active; a deleted offer whose time ran out
 * is announced as expired.
 */
@Service
@RequiredArgsConstructor
public class ProfileShareLifecycle {

  /**
   * Why the system withdrew an offer whose offerer may no longer make it. The Profile's other
   * managers read it, so it says nothing about the offerer's Account, such as whether that Account
   * was disabled or held ServerAdmin.
   */
  public static final String OFFERER_NO_LONGER_AUTHORIZED = "offerer no longer authorized";

  private final ProfileHouseholdShareRepository shareRepository;
  private final ApplicationEventPublisher events;

  /** Stores a new share at the instant; a pending one is announced as an offer made then. */
  public ProfileHouseholdShare create(ProfileHouseholdShare share, Instant now) {
    var created = shareRepository.saveAndFlush(share);
    events.publishEvent(
        ProfileShareChangedEvent.builder()
            .share(ChangedShare.of(created))
            .live(created.getStatus().isLive())
            .action(
                created.getStatus() == ProfileShareStatus.PENDING
                    ? new ShareAction.Offered()
                    : new ShareAction.Other())
            .changedAt(Optional.of(now))
            .build());
    return created;
  }

  /** Withdraws the Profile's pending offer to the Household so a new one can be offered. */
  public void supersedePending(UUID profileId, UUID householdId, Instant now) {
    announceStoppedWaiting(shareRepository.supersedePending(profileId, householdId, now), now);
  }

  /** Withdraws one pending offer with the reason it will later explain; false when not pending. */
  public boolean tryInvalidatePending(UUID shareId, String reason, Instant now) {
    return announced(
        shareRepository.tryInvalidatePending(shareId, reason, now),
        new ShareAction.OfferStoppedWaiting(),
        now);
  }

  /** Withdraws every unexpired pending offer the Account made; answers how many. */
  public int invalidatePendingOfferedBy(UUID offererAccountId, String reason, Instant now) {
    var withdrawn = shareRepository.invalidatePendingOfferedBy(offererAccountId, reason, now);
    announceStoppedWaiting(withdrawn, now);
    return withdrawn.size();
  }

  /** Stores EXPIRED on every offer whose time ran out; answers how many. */
  public int expireLapsedOffers(Instant now) {
    var expired = shareRepository.materializeExpiredPending(now);
    announceStoppedWaiting(expired, now);
    return expired.size();
  }

  /** The Account accepts a pending, unexpired offer; false when another decision won. */
  public boolean tryActivatePending(UUID shareId, UUID acceptingAccountId, Instant now) {
    return announced(
        shareRepository.tryActivatePending(shareId, now),
        new ShareAction.Answered(acceptingAccountId),
        now);
  }

  /**
   * The Account rejects a pending offer, which stores EXPIRED instead when its time has already run
   * out; false when it was no longer pending.
   */
  public boolean tryRejectPending(UUID shareId, UUID rejectingAccountId, Instant now) {
    return announced(
        shareRepository.tryDeclinePending(shareId, ProfileShareStatus.REJECTED, now),
        new ShareAction.Answered(rejectingAccountId),
        now);
  }

  /**
   * Cancels a pending offer, which stores EXPIRED instead when its time has already run out; false
   * when it was no longer pending.
   */
  public boolean tryCancelPending(UUID shareId, Instant now) {
    return announced(
        shareRepository.tryDeclinePending(shareId, ProfileShareStatus.CANCELED, now),
        new ShareAction.OfferStoppedWaiting(),
        now);
  }

  /** Ends an active share the way the ending says; false when it was not active. */
  public boolean tryEndActive(UUID shareId, ShareAction.Ending ending, Instant now) {
    return announced(shareRepository.tryEndActive(shareId, now), ending, now);
  }

  /** Makes the Profile's availability in its Account's Household the structural share. */
  public void ensureActiveMembershipShare(UUID profileId, UUID householdId, Instant now) {
    var changes = shareRepository.ensureActiveMembershipShare(profileId, householdId, now);
    announceStoppedWaiting(changes.offersStoppedWaiting(), now);
    changes.otherChanges().forEach(share -> announceWrite(share, new ShareAction.Other(), now));
  }

  /** Withdraws every pending offer of the Profile with the reason. */
  public void invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    announceStoppedWaiting(
        shareRepository.invalidatePendingByProfileId(profileId, reason, now), now);
  }

  /** Withdraws every pending offer into the Household with the reason. */
  public void invalidatePendingByHouseholdId(UUID householdId, String reason, Instant now) {
    announceStoppedWaiting(
        shareRepository.invalidatePendingByHouseholdId(householdId, reason, now), now);
  }

  /** Turns the Profile's structural share into the Household into an ordinary visitor share. */
  public void convertMembershipShareToVisitorShare(UUID profileId, UUID householdId) {
    shareRepository
        .convertMembershipShareToVisitorShare(profileId, householdId)
        .forEach(this::announce);
  }

  /** Withdraws the leaving manager's own pending offers of the Profile. */
  public void invalidatePendingOffersByProfileIdAndOffererAccountId(
      UUID profileId, UUID offererAccountId, String reason, Instant now) {
    announceStoppedWaiting(
        shareRepository.invalidatePendingOffersByProfileIdAndOffererAccountId(
            profileId, offererAccountId, reason, now),
        now);
  }

  /** Announces every share of the Profile, which the database deletes with the Profile. */
  public void announceDeletionWithProfile(UUID profileId, Instant now) {
    shareRepository
        .findByProfileIdAndReloadFromDatabase(profileId)
        .forEach(share -> announceRemoval(share, now));
  }

  /** Announces every share into the Household, which the database deletes with the Household. */
  public void announceDeletionWithHousehold(UUID householdId, Instant now) {
    shareRepository
        .findByHouseholdIdAndReloadFromDatabase(householdId)
        .forEach(share -> announceRemoval(share, now));
  }

  /**
   * Announces the Profile's shares into the Households it moves between. Whether the Profile is a
   * visitor there changes with the move, even for a share the move does not write.
   */
  public void announceMove(UUID profileId, UUID sourceHouseholdId, UUID destinationHouseholdId) {
    shareRepository.findByProfileId(profileId).stream()
        .filter(
            share ->
                share.getHouseholdId().equals(sourceHouseholdId)
                    || share.getHouseholdId().equals(destinationHouseholdId))
        .forEach(this::announceAsStored);
  }

  private boolean announced(Optional<ChangedShare> changed, ShareAction action, Instant now) {
    changed.ifPresent(share -> announceWrite(share, action, now));
    return changed.isPresent();
  }

  private void announceStoppedWaiting(List<ChangedShare> offers, Instant now) {
    offers.forEach(offer -> announceWrite(offer, new ShareAction.OfferStoppedWaiting(), now));
  }

  private void announceWrite(ChangedShare changed, ShareAction action, Instant now) {
    events.publishEvent(
        ProfileShareChangedEvent.builder()
            .share(changed)
            .live(true)
            .action(action)
            .changedAt(Optional.of(now))
            .build());
  }

  private void announce(ChangedShare changed) {
    events.publishEvent(new ProfileShareChangedEvent(changed, true));
  }

  private void announceAsStored(ProfileHouseholdShare share) {
    events.publishEvent(
        new ProfileShareChangedEvent(ChangedShare.of(share), share.getStatus().isLive()));
  }

  // An offer past its expiry that the sweep has not reached is still stored pending. Its removal is
  // announced as the expiry the sweep would have stored, the change that ends it, so its Household
  // hears that the offer expired and not that its Profile or the Household was deleted.
  private void announceRemoval(ProfileHouseholdShare share, Instant now) {
    var removed = ChangedShare.of(share).toBuilder().status(share.statusAt(now)).build();
    events.publishEvent(new ProfileShareChangedEvent(removed, share.getStatus().isLive(), true));
  }
}
