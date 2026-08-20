package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileAdministration(
    UUID id,
    String name,
    ProfileKind kind,
    Integer maximumAllowedRatingAge,
    String picture,
    boolean pinConfigured,
    boolean restricted,
    UUID householdId,
    boolean linked) {

  public static ProfileAdministration from(Profile profile, boolean linked) {
    return ProfileAdministration.builder()
        .id(profile.getId())
        .name(profile.getName())
        .kind(profile.getKind())
        .maximumAllowedRatingAge(profile.getMaximumAllowedRatingAge())
        .picture(profile.getPicture())
        .pinConfigured(profile.hasEffectivePin())
        .restricted(profile.isRestricted())
        .householdId(profile.getHouseholdId())
        .linked(linked)
        .build();
  }
}
