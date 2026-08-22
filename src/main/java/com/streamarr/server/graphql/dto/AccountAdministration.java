package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.UserAccount;
import java.util.UUID;
import lombok.Builder;

@Builder
public record AccountAdministration(
    UUID id,
    String email,
    String displayName,
    boolean enabled,
    boolean serverAdmin,
    HouseholdRole householdRole,
    UUID householdId,
    UUID personalProfileId) {

  public static AccountAdministration from(UserAccount account) {
    return AccountAdministration.builder()
        .id(account.getId())
        .email(account.getEmail())
        .displayName(account.getDisplayName())
        .enabled(account.isEnabled())
        .serverAdmin(account.isServerAdmin())
        .householdRole(account.getHouseholdRole())
        .householdId(account.getHouseholdId())
        .personalProfileId(account.getPersonalProfileId())
        .build();
  }
}
