package com.streamarr.server.domain.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * A share that one write changed, with the Profile it shares, the Household it is into, the status
 * the write left it in, the Account that made the offer while that Account exists, and when the
 * offer expires. A share announced without being written, as it is moved or deleted, carries what
 * it is stored with, except that a deleted offer whose time ran out carries EXPIRED.
 */
@Builder(toBuilder = true)
public record ChangedShare(
    @NonNull UUID shareId,
    @NonNull UUID profileId,
    @NonNull UUID householdId,
    @NonNull ProfileShareStatus status,
    @NonNull Optional<UUID> offeredByAccountId,
    @NonNull Optional<Instant> expiresAt) {

  public static ChangedShare of(ProfileHouseholdShare share) {
    return builder()
        .shareId(share.getId())
        .profileId(share.getProfileId())
        .householdId(share.getHouseholdId())
        .status(share.getStatus())
        .offeredByAccountId(Optional.ofNullable(share.getOfferedByAccountId()))
        .expiresAt(Optional.ofNullable(share.getExpiresAt()))
        .build();
  }

  /** A share that no Account offered and that never expires, unless the builder names them. */
  public static class ChangedShareBuilder {
    private Optional<UUID> offeredByAccountId = Optional.empty();
    private Optional<Instant> expiresAt = Optional.empty();
  }
}
