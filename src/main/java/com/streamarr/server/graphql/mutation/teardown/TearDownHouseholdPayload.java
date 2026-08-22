package com.streamarr.server.graphql.mutation.teardown;

import java.util.List;
import java.util.Optional;

public record TearDownHouseholdPayload(
    Optional<String> householdId, List<TearDownHouseholdError> userErrors) {}
