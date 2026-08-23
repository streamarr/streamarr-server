package com.streamarr.server.graphql.inputs;

public record TearDownHouseholdInput(
    String householdId, String reason, LastAccountInput lastAccount) {

  public record LastAccountInput(
      LastAccountAction choice,
      String destinationHouseholdId,
      String replacementManagerAccountId) {}
}
