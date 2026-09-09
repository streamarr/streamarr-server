package com.streamarr.server.graphql.mutation.household.deletion;

import java.util.List;
import java.util.Optional;

public record TransferLastAccountAndDeleteHouseholdPayload(
    Optional<String> deletedHouseholdId,
    List<TransferLastAccountAndDeleteHouseholdError> userErrors) {}
