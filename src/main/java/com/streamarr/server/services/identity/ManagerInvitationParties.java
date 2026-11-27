package com.streamarr.server.services.identity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * The Profile and the inviter a manager invitation names, with the names it stored when it was
 * made. A party that is deleted is absent, and its stored name stands in for it.
 */
@Builder
public record ManagerInvitationParties(
    @NonNull Optional<UUID> profileId,
    @NonNull String storedProfileName,
    @NonNull Optional<UUID> inviterAccountId,
    @NonNull String storedInviterDisplayName) {

  /** The names to show: each party's current name, or the stored one once the party is gone. */
  ManagerInvitationNames namesAmong(
      Map<UUID, String> profileNames, Map<UUID, String> inviterDisplayNames) {
    return ManagerInvitationNames.builder()
        .profileName(profileId.map(profileNames::get).orElse(storedProfileName))
        .inviterDisplayName(
            inviterAccountId.map(inviterDisplayNames::get).orElse(storedInviterDisplayName))
        .build();
  }
}
