package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.Household;
import java.util.UUID;

public record HouseholdAdministration(UUID id, String name) {

  public static HouseholdAdministration from(Household household) {
    return new HouseholdAdministration(household.getId(), household.getName());
  }
}
