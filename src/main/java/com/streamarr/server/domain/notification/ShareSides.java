package com.streamarr.server.domain.notification;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One share and its two sides as they stand now (ADR 0063 §The sharing kinds). The offering side is
 * the Profile's ProfileManagers, direct and derived, and the Account that made the offer; the
 * Household's side is the target Household's HouseholdAdmins. An Account can stand on both. The
 * Account that made the offer is empty once that Account is deleted.
 */
@Builder
public record ShareSides(
    @NonNull ProfileShareStatus status,
    @NonNull String profileName,
    @NonNull Optional<UUID> offeredByAccountId,
    @NonNull Set<UUID> offeringSide,
    @NonNull Set<UUID> householdSide) {

  public ShareSides {
    offeringSide = Set.copyOf(offeringSide);
    householdSide = Set.copyOf(householdSide);
  }
}
