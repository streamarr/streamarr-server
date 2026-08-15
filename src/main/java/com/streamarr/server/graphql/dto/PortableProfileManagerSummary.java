package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManager;
import java.util.UUID;

public record PortableProfileManagerSummary(UUID id, UUID accountId, UUID profileId) {

  /**
   * Creates a portable summary from a profile manager.
   *
   * @param manager the profile manager whose identifiers are copied
   * @return a summary containing the manager, account, and profile identifiers
   */
  public static PortableProfileManagerSummary from(ProfileManager manager) {
    return new PortableProfileManagerSummary(
        manager.getId(), manager.getAccountId(), manager.getProfileId());
  }
}
