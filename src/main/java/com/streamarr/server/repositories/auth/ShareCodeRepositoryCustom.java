package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.domain.pagination.Seek;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Share code writes (ADR 0024 §Share codes). Every transition leaves LIVE only from a LIVE,
 * unexpired code, in one conditional statement, so of two callers that move one code exactly one
 * wins.
 */
public interface ShareCodeRepositoryCustom {

  /**
   * Stores the unsaved live code unless a stored code already has its digest; answers the new
   * code's id, or empty on that collision. Retrying with another code stays in the same
   * transaction.
   */
  Optional<UUID> tryInsert(ShareCode unsaved);

  /** Spends a live code on the offer it admits; false when it was not live. */
  boolean trySpend(UUID shareCodeId, Instant now);

  /** Withdraws a live code; false when it was not live. */
  boolean tryWithdraw(UUID shareCodeId, Instant now);

  /** The Households for which the issuer holds codes that are live at the instant. */
  List<UUID> findHouseholdIdsWithLiveCodesIssuedBy(UUID issuerAccountId, Instant now);

  /** Invalidates every code the issuer holds live, in one statement; answers how many. */
  int invalidateLiveIssuedBy(UUID issuerAccountId, Instant now);

  /**
   * Invalidates every code the issuer holds live for one Household, in one statement; answers how
   * many.
   */
  int invalidateLiveIssuedFor(UUID issuerAccountId, UUID householdId, Instant now);

  /** The codes of one Household that are live at the instant, newest first, beyond the seek. */
  List<Row<ShareCode>> liveRowsInHousehold(UUID householdId, Instant now, Seek seek);

  /** Reads the code past any stale managed copy this transaction holds. */
  Optional<ShareCode> findByIdAndReloadFromDatabase(UUID shareCodeId);
}
