package com.streamarr.server.fixtures;

import com.streamarr.server.domain.auth.Household;
import java.util.UUID;

public final class HouseholdFixture {

  private HouseholdFixture() {}

  public static Household.HouseholdBuilder<?, ?> defaultHouseholdBuilder() {
    return Household.builder().name("Household-" + UUID.randomUUID());
  }
}
