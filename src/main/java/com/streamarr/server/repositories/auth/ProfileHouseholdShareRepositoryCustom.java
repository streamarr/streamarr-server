package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareStatusFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
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

  /** The shares into the Household that the filter keeps, beyond the seek, by creation, then ID. */
  List<Row<ProfileHouseholdShare>> rowsIntoHousehold(
      UUID householdId, ShareStatusFilter filter, Seek seek);

  /** The Profile's shares that the filter keeps, beyond the seek, by creation time, then ID. */
  List<Row<ProfileHouseholdShare>> rowsOfProfile(
      UUID profileId, ShareStatusFilter filter, Seek seek);

  /**
   * The PENDING share of the Profile into the Household becomes CANCELED (EXPIRED when already
   * past) so a new one can be offered; answers the shares that changed.
   */
  List<ChangedShare> supersedePending(UUID profileId, UUID householdId, Instant now);

  /** One PENDING share becomes INVALIDATED with the reason it will later explain. */
  Optional<ChangedShare> tryInvalidatePending(UUID shareId, String reason, Instant now);

  /** Every unexpired PENDING share the Account offered becomes INVALIDATED with the reason. */
  List<ChangedShare> invalidatePendingOfferedBy(UUID offererAccountId, String reason, Instant now);

  /**
   * Re-reads the share past the first-level cache after jOOQ DML changed it in this transaction.
   */
  Optional<ProfileHouseholdShare> findByIdAndReloadFromDatabase(UUID shareId);

  /** PENDING and unexpired becomes ACTIVE; a raced decision has exactly one winner. */
  Optional<ChangedShare> tryActivatePending(UUID shareId, Instant now);

  /** PENDING becomes REJECTED or CANCELED — or EXPIRED when its time has already passed. */
  Optional<ChangedShare> tryDeclinePending(UUID shareId, ProfileShareStatus target, Instant now)
      throws IllegalArgumentException;

  static void requireDeclineTarget(ProfileShareStatus target) {
    if (target != ProfileShareStatus.REJECTED && target != ProfileShareStatus.CANCELED) {
      throw new IllegalArgumentException("A decline target must be REJECTED or CANCELED.");
    }
  }

  /**
   * ACTIVE becomes ENDED. At commit, the database rejects ending a share required by membership.
   */
  Optional<ChangedShare> tryEndActive(UUID shareId, Instant now);

  /**
   * Linking makes the Profile someone's Personal Profile: its home availability becomes the
   * structural share, whether it was active, still pending, or missing.
   */
  List<ChangedShare> ensureActiveMembershipShare(UUID profileId, UUID householdId, Instant now);

  /** Stores EXPIRED on every PENDING share whose offer time has run out. */
  List<ChangedShare> materializeExpiredPending(Instant now);

  /** Every PENDING share of the Profile becomes INVALIDATED (linked, transferred, or deleted). */
  List<ChangedShare> invalidatePendingByProfileId(UUID profileId, String reason, Instant now);

  /**
   * KEEP_AS_VISITOR and PRESERVE_PROFILE cleanup turn the old structural availability into an
   * ordinary visitor share instead of ending it.
   */
  List<ChangedShare> convertMembershipShareToVisitorShare(UUID profileId, UUID householdId);

  /** The leaving manager's own PENDING shares of the Profile become INVALIDATED. */
  List<ChangedShare> invalidatePendingOffersByProfileIdAndOffererAccountId(
      UUID profileId, UUID offererAccountId, String reason, Instant now);
}
