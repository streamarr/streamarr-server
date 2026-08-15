package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import java.util.UUID;

public record PortableProfileSummary(
    UUID id, String name, ProfileKind kind, Integer maximumAllowedRatingAge, boolean pinProtected) {

  public static PortableProfileSummary from(Profile profile) {
    return new PortableProfileSummary(
        profile.getId(),
        profile.getName(),
        profile.getKind(),
        profile.getMaximumAllowedRatingAge(),
        profile.getPinHash() != null);
  }
}
