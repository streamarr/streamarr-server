package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.HouseholdAdministration;
import java.util.List;
import java.util.Optional;

public record CreateHouseholdPayload(
    Optional<HouseholdAdministration> household, List<CreateHouseholdError> userErrors) {}
