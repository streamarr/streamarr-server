package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.auth.Household;
import java.util.UUID;

public record PortableHouseholdSummary(UUID id, String name) {

  public static PortableHouseholdSummary from(Household household) {
    return new PortableHouseholdSummary(household.getId(), household.getName());
  }
}
