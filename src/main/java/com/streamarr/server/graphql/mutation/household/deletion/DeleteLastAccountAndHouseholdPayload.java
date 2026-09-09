package com.streamarr.server.graphql.mutation.household.deletion;

import java.util.List;
import java.util.Optional;

public record DeleteLastAccountAndHouseholdPayload(
    Optional<String> deletedHouseholdId, List<DeleteLastAccountAndHouseholdError> userErrors) {}
