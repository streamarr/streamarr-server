package com.streamarr.server.repositories.auth;

import java.util.Optional;
import java.util.UUID;

/** Reads the authoritative version counters that token validation checks claims against. */
public interface VersionCounterReader {

  Optional<Long> sessionVersion(UUID sessionId);

  Optional<Long> membershipVersion(UUID accountId, UUID householdId);

  Optional<Long> profilePolicyVersion(UUID profileId);
}
