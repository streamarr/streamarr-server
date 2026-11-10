package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareStatusFilter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;

/**
 * The shares of one Profile, or into one Household, kept to the given statuses; no statuses keeps
 * every share. A cursor continues only the scope that issued it, so the statuses keep one order.
 */
public record ShareListScope(@NonNull UUID id, @NonNull List<ProfileShareStatus> statuses) {

  public ShareListScope {
    statuses = statuses.stream().distinct().sorted().toList();
  }

  /** The filter that reads each share's status as it stands at {@code now}. */
  public ShareStatusFilter filterAt(Instant now) {
    return new ShareStatusFilter(Set.copyOf(statuses), now);
  }
}
