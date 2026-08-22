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

  /** Whether any share of the Profile is still ACTIVE or PENDING, read as a scalar. */
  boolean hasLiveOrPendingShares(UUID profileId);

  /** A bounded keyset window of pending offers into one Household. */
  List<ProfileHouseholdShare> findHouseholdPage(
      UUID householdId, ProfileShareStatus status, KeysetPaginationOptions options);

  /** A bounded keyset window of every share for one Profile. */
  List<ProfileHouseholdShare> findProfilePage(UUID profileId, KeysetPaginationOptions options);

  /** Retires an older PENDING offer for the pair as EXPIRED or CANCELED before replacement. */
  int retirePendingForPair(UUID profileId, UUID householdId, Instant now);

  /** Invalidates one PENDING offer while preserving the reason for reporting. */
  boolean tryInvalidate(UUID shareId, String reason, Instant now);

  /** Refreshes a possibly managed row after jOOQ DML changed it in this transaction. */
  Optional<ProfileHouseholdShare> findFreshById(UUID shareId);

  /** Activates one PENDING, unexpired offer; a raced decision has exactly one winner. */
  boolean tryActivate(UUID shareId, Instant now);

  /** Moves one PENDING offer to REJECTED or CANCELED. */
  boolean tryDecline(UUID shareId, ProfileShareStatus target, Instant now)
      throws IllegalArgumentException;

  static void requireDeclineTarget(ProfileShareStatus target) {
    if (target != ProfileShareStatus.REJECTED && target != ProfileShareStatus.CANCELED) {
      throw new IllegalArgumentException("A decline target must be REJECTED or CANCELED.");
    }
  }

  /** Ends one ACTIVE share. The deferred T3 judges structural shares at commit. */
  boolean tryEnd(UUID shareId, Instant now);

  /**
   * Connecting makes the Profile someone's Personal Profile: its home availability becomes the
   * structural share, whether it was active, still pending, or missing.
   */
  void upsertStructuralHomeShare(UUID profileId, UUID householdId, Instant now);

  /** Invalidates every PENDING offer of the Profile (connected, transferred, or deleted). */
  int invalidatePendingSharesForProfile(UUID profileId, String reason, Instant now);
}
