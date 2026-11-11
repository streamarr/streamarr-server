package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.ShareStatusFilter;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;

/**
 * The shares of one Profile, or into one Household, kept to the given statuses; no statuses keeps
 * every share. A cursor continues only the scope that issued it, so the statuses keep one order.
 */
public record ShareListScope(@NonNull UUID id, @NonNull List<ProfileShareStatus> statuses) {

  private static final List<ProfileShareStatus> LIVE =
      Arrays.stream(ProfileShareStatus.values()).filter(ProfileShareStatus::isLive).toList();

  public ShareListScope {
    statuses = statuses.stream().distinct().sorted().toList();
  }

  /** The filter that reads each share's status as it stands at {@code now}. */
  public ShareStatusFilter filterAt(Instant now) {
    return new ShareStatusFilter(Set.copyOf(statuses), now);
  }

  /**
   * The same scope kept to the offers still pending and the active shares, or nothing when every
   * status it names is over.
   */
  public Optional<ShareListScope> keptToLive() {
    var live =
        statuses.isEmpty() ? LIVE : statuses.stream().filter(ProfileShareStatus::isLive).toList();
    return live.isEmpty() ? Optional.empty() : Optional.of(new ShareListScope(id, live));
  }
}
