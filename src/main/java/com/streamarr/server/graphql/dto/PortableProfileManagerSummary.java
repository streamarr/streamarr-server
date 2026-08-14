package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManager;
import java.util.UUID;

public record PortableProfileManagerSummary(UUID id, UUID accountId, UUID profileId) {

  public static PortableProfileManagerSummary from(ProfileManager manager) {
    return new PortableProfileManagerSummary(
        manager.getId(), manager.getAccountId(), manager.getProfileId());
  }
}
