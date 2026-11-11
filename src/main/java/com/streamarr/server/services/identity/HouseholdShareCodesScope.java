package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ShareCodeFilter;
import com.streamarr.server.domain.auth.ShareCodeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;

/**
 * The share codes of one Household kept to the given statuses; no statuses keeps every code. A
 * cursor continues only the scope that issued it, so the statuses keep one order.
 */
public record HouseholdShareCodesScope(
    @NonNull UUID householdId, @NonNull List<ShareCodeStatus> statuses) {

  public HouseholdShareCodesScope {
    statuses = statuses.stream().distinct().sorted().toList();
  }

  /** The filter that reads each code's status as it stands at {@code now}. */
  public ShareCodeFilter filterAt(Instant now) {
    return new ShareCodeFilter(householdId, Set.copyOf(statuses), now);
  }
}
