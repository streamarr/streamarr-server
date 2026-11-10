package com.streamarr.server.domain.auth;

import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import lombok.NonNull;

/**
 * The statuses a list of shares keeps, each share read as it stands at one instant: an offer past
 * its expiry reads as EXPIRED, never PENDING. A filter that names no status keeps every share.
 */
public record ShareStatusFilter(@NonNull Set<ProfileShareStatus> statuses, @NonNull Instant at) {

  public ShareStatusFilter {
    statuses =
        statuses.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(statuses));
  }

  public boolean keeps(ProfileHouseholdShare share) {
    return statuses.isEmpty() || statuses.contains(share.statusAt(at));
  }
}
