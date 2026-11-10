package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Every write of a Profile share (ADR 0024 §Profile sharing). The workflows that offer, decide,
 * end, link, move and delete call it inside their own transactions; no other class writes a share.
 */
@Service
@RequiredArgsConstructor
public class ProfileShareLifecycle {

  private final ProfileHouseholdShareRepository shareRepository;

  /** Stores a new share. */
  public ProfileHouseholdShare create(ProfileHouseholdShare share) {
    return shareRepository.saveAndFlush(share);
  }

  /** Withdraws the Profile's pending offer to the Household so a new one can be offered. */
  public void supersedePending(UUID profileId, UUID householdId, Instant now) {
    shareRepository.supersedePending(profileId, householdId, now);
  }

  /** Withdraws one pending offer with the reason it will later explain; false when not pending. */
  public boolean tryInvalidatePending(UUID shareId, String reason, Instant now) {
    return shareRepository.tryInvalidatePending(shareId, reason, now).isPresent();
  }

  /** Withdraws every unexpired pending offer the Account made; answers how many. */
  public int invalidatePendingOfferedBy(UUID offererAccountId, String reason, Instant now) {
    return shareRepository.invalidatePendingOfferedBy(offererAccountId, reason, now).size();
  }

  /** Accepts a pending, unexpired offer; false when another decision won. */
  public boolean tryActivatePending(UUID shareId, Instant now) {
    return shareRepository.tryActivatePending(shareId, now).isPresent();
  }

  /** Declines or cancels a pending offer; false when it was no longer pending. */
  public boolean tryDeclinePending(UUID shareId, ProfileShareStatus target, Instant now) {
    return shareRepository.tryDeclinePending(shareId, target, now).isPresent();
  }

  /** Ends an active share; false when it was not active. */
  public boolean tryEndActive(UUID shareId, Instant now) {
    return shareRepository.tryEndActive(shareId, now).isPresent();
  }

  /** Makes the Profile's availability in its Account's Household the structural share. */
  public void ensureActiveMembershipShare(UUID profileId, UUID householdId, Instant now) {
    shareRepository.ensureActiveMembershipShare(profileId, householdId, now);
  }

  /** Withdraws every pending offer of the Profile with the reason. */
  public void invalidatePendingByProfileId(UUID profileId, String reason, Instant now) {
    shareRepository.invalidatePendingByProfileId(profileId, reason, now);
  }

  /** Turns the Profile's structural share into the Household into an ordinary visitor share. */
  public void convertMembershipShareToVisitorShare(UUID profileId, UUID householdId) {
    shareRepository.convertMembershipShareToVisitorShare(profileId, householdId);
  }

  /** Withdraws the leaving manager's own pending offers of the Profile. */
  public void invalidatePendingOffersByProfileIdAndOffererAccountId(
      UUID profileId, UUID offererAccountId, String reason, Instant now) {
    shareRepository.invalidatePendingOffersByProfileIdAndOffererAccountId(
        profileId, offererAccountId, reason, now);
  }
}
