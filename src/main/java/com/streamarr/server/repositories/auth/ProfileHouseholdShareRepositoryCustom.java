package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.time.Instant;
import java.util.UUID;

public interface ProfileHouseholdShareRepositoryCustom {

  /** Whether the Profile is available in the Household right now, read as a scalar. */
  boolean isActivelyShared(UUID profileId, UUID householdId);

  /** Locks and reports the active share used as authorization authority. */
  boolean lockActiveShare(UUID profileId, UUID householdId);

  /** Whether any share of the Profile is still ACTIVE or PENDING, read as a scalar. */
  boolean hasLiveOrPendingShares(UUID profileId);

  /** Activates one PENDING, unexpired offer; a raced decision has exactly one winner. */
  boolean tryActivate(UUID shareId, Instant now);

  /** Moves one PENDING offer to REJECTED or CANCELED. */
  boolean tryDecline(UUID shareId, ProfileShareStatus target, Instant now);

  /** Ends one ACTIVE share. The deferred T3 judges structural shares at commit. */
  boolean tryEnd(UUID shareId, Instant now);
}
