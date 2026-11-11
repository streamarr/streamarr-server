package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.ShareCode;
import java.time.Instant;
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

  /** Invalidates a live code whose issuer may no longer issue it; false when it was not live. */
  boolean tryInvalidate(UUID shareCodeId, Instant now);

  /** Reads the code past any stale managed copy this transaction holds. */
  Optional<ShareCode> findByIdAndReloadFromDatabase(UUID shareCodeId);
}
