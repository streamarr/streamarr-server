package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.services.auth.PortableIdentityQueryService.ProfileShareView;
import java.util.UUID;

public record PortableProfileShareSummary(
    UUID id,
    UUID profileId,
    UUID householdId,
    ProfileShareStatus status,
    PortableProfileSummary profile,
    PortableHouseholdSummary household) {

  public static PortableProfileShareSummary from(ProfileHouseholdShare share) {
    return new PortableProfileShareSummary(
        share.getId(), share.getProfileId(), share.getHouseholdId(), share.getStatus(), null, null);
  }

  public static PortableProfileShareSummary from(ProfileShareView view) {
    var share = view.share();
    return new PortableProfileShareSummary(
        share.getId(),
        share.getProfileId(),
        share.getHouseholdId(),
        share.getStatus(),
        PortableProfileSummary.from(view.profile()),
        PortableHouseholdSummary.from(view.household()));
  }
}
