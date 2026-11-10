package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.services.identity.SharedProfile;
import java.util.UUID;
import lombok.Builder;

@Builder
public record ProfileSummary(
    UUID id,
    String name,
    String picture,
    ProfileKind kind,
    boolean personalProfile,
    boolean restricted) {

  public static ProfileSummary from(SharedProfile shared) {
    var profile = shared.profile();
    return ProfileSummary.builder()
        .id(profile.getId())
        .name(profile.getName())
        .picture(profile.getPicture())
        .kind(profile.getKind())
        .personalProfile(shared.personalProfile())
        .restricted(profile.isRestricted())
        .build();
  }
}
