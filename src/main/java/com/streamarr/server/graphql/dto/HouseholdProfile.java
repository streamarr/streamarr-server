package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.identity.HouseholdProfileDetails;

public record HouseholdProfile(ProfileAdministration profile, boolean visitor, boolean locked) {

  public static HouseholdProfile from(HouseholdProfileDetails details) {
    return new HouseholdProfile(
        ProfileAdministration.from(
            details.administration().profile(), details.administration().linked()),
        details.visitor(),
        details.locked());
  }
}
