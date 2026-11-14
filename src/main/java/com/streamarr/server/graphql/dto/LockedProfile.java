package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.Profile;
import java.util.UUID;

public record LockedProfile(UUID id, String name, String picture) {

  public static LockedProfile from(Profile profile) {
    return new LockedProfile(profile.getId(), profile.getName(), profile.getPicture());
  }
}
