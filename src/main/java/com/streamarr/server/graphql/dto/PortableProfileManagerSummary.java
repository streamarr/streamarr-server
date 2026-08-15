package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.services.auth.PortableIdentityQueryService.ProfileManagerView;
import java.util.UUID;

public record PortableProfileManagerSummary(
    UUID id,
    UUID accountId,
    UUID profileId,
    PortableProfileSummary profile,
    PortableAccountSummary account) {

  public static PortableProfileManagerSummary from(ProfileManager manager) {
    return new PortableProfileManagerSummary(
        manager.getId(), manager.getAccountId(), manager.getProfileId(), null, null);
  }

  public static PortableProfileManagerSummary from(ProfileManagerView view) {
    var manager = view.manager();
    return new PortableProfileManagerSummary(
        manager.getId(),
        manager.getAccountId(),
        manager.getProfileId(),
        PortableProfileSummary.from(view.profile()),
        PortableAccountSummary.from(view.account()));
  }
}
