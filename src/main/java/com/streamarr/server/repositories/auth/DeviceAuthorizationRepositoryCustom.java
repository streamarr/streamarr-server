package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.DeviceAuthorization;
import com.streamarr.server.domain.auth.DeviceAuthorizationStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface DeviceAuthorizationRepositoryCustom {

  /**
   * Loads the authorization for update, serializing every poller and the approving writer on one
   * row. Classification and mutation then happen against state nothing else can change mid-decision
   * — a chain of conditional updates would let an approval land between a claim and a cadence bump
   * and report a live grant as expired.
   */
  Optional<DeviceAuthorization> lockByDeviceCodeDigest(String deviceCodeDigest);

  /**
   * Records a decision on a still-pending, unexpired row in one statement. Returns the affected row
   * count so the caller re-reads only to classify a miss.
   */
  int decide(DeviceAuthorizationDecisionCommand command);

  /** Advances the poll cadence, and with it the interval a caller that polled early must adopt. */
  void updateCadence(UUID id, int pollIntervalSeconds, Instant nextPollAt, Instant now);

  /** Marks the row consumed once a poll has won it and the session exists. */
  void markConsumed(UUID id, Instant now);

  /** Counts outstanding pending codes — the DB-backed, cross-instance issuance cap. */
  int countOutstanding(Instant now);

  /**
   * Counts and inserts as one indivisible unit, so the outstanding cap is a hard limit rather than
   * a suggestion. The result carries both the insertion decision and the outstanding count observed
   * while holding the lock, so callers never need a racy post-transaction recount.
   *
   * <p>Counting in Java and then inserting is the check-then-act race AGENTS.md names: under READ
   * COMMITTED every concurrent caller reads the same pre-commit count and every one of them
   * inserts. A plain {@code INSERT ... SELECT WHERE (count) < cap} does not fix it either — the
   * subquery takes its own snapshot and sees none of the in-flight inserts. Serializing issuance on
   * a database advisory lock does, and issuance is a human pairing a TV, so the contention cost is
   * nil.
   */
  DeviceAuthorizationInsertResult tryInsertWithinCap(DeviceAuthorizationInsertCommand command);

  /**
   * The expiry of the oldest outstanding code: with a row-count cap there is no window to measure,
   * so this is the moment capacity provably frees.
   */
  Optional<Instant> findOldestOutstandingExpiry(Instant now);

  /** Deletes every row past its TTL regardless of terminal status. */
  int deleteExpired(Instant cutoff);

  /** Reads a row's status without loading it as a managed entity. */
  Optional<DeviceAuthorizationStatus> findStatusByUserCode(String userCode);
}
