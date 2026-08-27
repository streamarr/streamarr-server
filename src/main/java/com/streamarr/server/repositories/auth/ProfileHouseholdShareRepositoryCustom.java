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

  /** Whether the Profile has any ACTIVE share or unexpired PENDING share, read as a scalar. */
  boolean hasActiveOrPendingShares(UUID profileId, Instant now);

  /** The keyset window of the Household's PENDING shares that are not past expires_at. */
  List<ProfileHouseholdShare> findPendingByHouseholdId(
      UUID householdId, Instant now, KeysetPaginationOptions options);

  /** The keyset window of the Profile's shares in every status. */
  List<ProfileHouseholdShare> findByProfileId(UUID profileId, KeysetPaginationOptions options);

  /**
   * The PENDING share of the Profile into the Household becomes CANCELED (EXPIRED when already
   * past) so a new one can be offered; answers how many rows that was.
   */
  int supersedePending(UUID profileId, UUID householdId, Instant now);

  /** One PENDING share becomes INVALIDATED with the reason it will later explain. */
  boolean tryInvalidatePending(UUID shareId, String reason, Instant now);

  /** Every unexpired PENDING share the Account offered becomes INVALIDATED with the reason. */
  int invalidatePendingOfferedBy(UUID offererAccountId, String reason, Instant now);

  /**
   * Re-reads the share past the first-level cache after jOOQ DML changed it in this transaction.
   */
  Optional<ProfileHouseholdShare> findRefreshedById(UUID shareId);

  /** PENDING and unexpired becomes ACTIVE; a raced decision has exactly one winner. */
  boolean tryActivatePending(UUID shareId, Instant now);

  /** PENDING becomes REJECTED or CANCELED — or EXPIRED when its time has already passed. */
  boolean tryDeclinePending(UUID shareId, ProfileShareStatus target, Instant now)
      throws IllegalArgumentException;

  static void requireDeclineTarget(ProfileShareStatus target) {
    if (target != ProfileShareStatus.REJECTED && target != ProfileShareStatus.CANCELED) {
      throw new IllegalArgumentException("A decline target must be REJECTED or CANCELED.");
    }
  }

  /** ACTIVE becomes ENDED. The deferred T3 judges structural shares at commit. */
  boolean tryEndActive(UUID shareId, Instant now);

  /**
   * Connecting makes the Profile someone's Personal Profile: its home availability becomes the
   * structural share, whether it was active, still pending, or missing.
   */
  void upsertStructural(UUID profileId, UUID householdId, Instant now);

  /**
   * Every PENDING share of the Profile becomes INVALIDATED (connected, transferred, or deleted).
   */
  int invalidatePendingByProfileId(UUID profileId, String reason, Instant now);

  /**
   * KEEP_AS_VISITOR and KEEP-deletion turn the old structural availability into an ordinary visitor
   * share instead of ending it.
   */
  boolean tryDemoteStructural(UUID profileId, UUID householdId, Instant now);

  /** The leaving manager's own PENDING shares of the Profile become INVALIDATED. */
  int invalidatePendingByProfileIdOfferedBy(
      UUID profileId, UUID offererAccountId, String reason, Instant now);
}
