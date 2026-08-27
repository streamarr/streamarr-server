package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileHouseholdShareRepositoryCustom {

  /** Whether the Profile is available in the Household right now, read as a scalar. */
  boolean isActivelyShared(UUID profileId, UUID householdId);

  /** Locks and reports the active share used as authorization authority. */
  boolean lockActiveShare(UUID profileId, UUID householdId);

  /** Whether any share of the Profile is ACTIVE or an unexpired PENDING offer, read as a scalar. */
  boolean hasLiveOrPendingShares(UUID profileId, Instant now);

  /** A bounded keyset window of the unexpired PENDING offers into one Household. */
  List<ProfileHouseholdShare> findPendingOffersPage(
      UUID householdId, Instant now, KeysetPaginationOptions options);

  /** A bounded keyset window of every share for one Profile. */
  List<ProfileHouseholdShare> findProfilePage(UUID profileId, KeysetPaginationOptions options);

  /** Retires an older PENDING offer for the pair as EXPIRED or CANCELED before replacement. */
  int retirePendingForPair(UUID profileId, UUID householdId, Instant now);

  /** Invalidates one PENDING offer, recording why so the offer can explain itself later. */
  boolean tryInvalidate(UUID shareId, String reason, Instant now);

  /** Invalidates every unexpired PENDING offer the Account made, recording why. */
  int invalidatePendingOffersOfferedBy(UUID offererAccountId, String reason, Instant now);

  /** Refreshes a possibly managed row after jOOQ DML changed it in this transaction. */
  Optional<ProfileHouseholdShare> findFreshById(UUID shareId);

  /** Activates one PENDING, unexpired offer; a raced decision has exactly one winner. */
  boolean tryActivate(UUID shareId, Instant now);

  /** Moves one PENDING offer to REJECTED or CANCELED — or to EXPIRED when its time has passed. */
  boolean tryDecline(UUID shareId, ProfileShareStatus target, Instant now)
      throws IllegalArgumentException;

  static void requireDeclineTarget(ProfileShareStatus target) {
    if (target != ProfileShareStatus.REJECTED && target != ProfileShareStatus.CANCELED) {
      throw new IllegalArgumentException("A decline target must be REJECTED or CANCELED.");
    }
  }

  /** Ends one ACTIVE share. The deferred T3 judges structural shares at commit. */
  boolean tryEnd(UUID shareId, Instant now);
}
