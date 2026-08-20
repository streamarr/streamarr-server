package com.streamarr.server.graphql.mutation.teardown;

import java.util.List;

public record TearDownHouseholdPayload(
    String householdId, List<TearDownHouseholdError> userErrors) {}
