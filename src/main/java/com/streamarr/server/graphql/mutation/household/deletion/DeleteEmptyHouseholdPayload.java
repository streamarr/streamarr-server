package com.streamarr.server.graphql.mutation.household.deletion;

import java.util.List;
import java.util.Optional;

public record DeleteEmptyHouseholdPayload(
    Optional<String> deletedHouseholdId, List<DeleteEmptyHouseholdError> userErrors) {}
