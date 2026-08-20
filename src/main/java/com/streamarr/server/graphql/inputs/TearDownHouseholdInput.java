package com.streamarr.server.graphql.inputs;

import com.streamarr.server.services.identity.HouseholdTeardownService.FinalAccountChoice;

public record TearDownHouseholdInput(
    String householdId, String reason, FinalAccountInput finalAccount) {

  public record FinalAccountInput(
      FinalAccountChoice choice,
      String destinationHouseholdId,
      String replacementManagerAccountId) {}
}
