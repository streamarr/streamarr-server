package com.streamarr.server.graphql.mutation.household.deletion;

import java.util.List;
import java.util.Optional;

public record DeleteLastAccountAndHouseholdPreservingPersonalProfilePayload(
    Optional<String> deletedHouseholdId,
    List<DeleteLastAccountAndHouseholdPreservingPersonalProfileError> userErrors) {}
