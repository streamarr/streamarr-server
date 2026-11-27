package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.identity.ManagedProfileDetails;

public record ManagedProfile(ProfileAdministration profile, boolean available) {

  public static ManagedProfile from(ManagedProfileDetails details) {
    return new ManagedProfile(
        ProfileAdministration.from(
            details.administration().profile(), details.administration().linked()),
        details.available());
  }
}
