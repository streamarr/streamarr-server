package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import java.util.UUID;

public record PortableProfileShareSummary(
    UUID id, UUID profileId, UUID householdId, ProfileShareStatus status) {

  public static PortableProfileShareSummary from(ProfileHouseholdShare share) {
    return new PortableProfileShareSummary(
        share.getId(), share.getProfileId(), share.getHouseholdId(), share.getStatus());
  }
}
