package com.streamarr.server.domain.auth;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;

/**
 * The share codes of one Household that a list keeps, each code read as it stands at one instant: a
 * live code past its expiry reads as EXPIRED, never LIVE. A filter that names no status keeps every
 * code.
 */
public record ShareCodeFilter(
    @NonNull UUID householdId, @NonNull Set<ShareCodeStatus> statuses, @NonNull Instant at) {

  public ShareCodeFilter {
    statuses =
        statuses.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(statuses));
  }
}
