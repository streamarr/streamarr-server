package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdShareFilter;
import java.time.Instant;
import java.util.UUID;
import lombok.NonNull;

/**
 * The shares into one Household in the given statuses, all of them or only those of visitors:
 * Profiles that belong to another Household. A cursor continues only the scope that issued it.
 */
public record HouseholdSharesScope(@NonNull ShareListScope shares, boolean visitorsOnly) {

  public UUID householdId() {
    return shares.id();
  }

  /** The filter that reads each share's status as it stands at {@code now}. */
  public HouseholdShareFilter filterAt(Instant now) {
    return new HouseholdShareFilter(shares.id(), shares.filterAt(now), visitorsOnly);
  }
}
