package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.HouseholdAdministration;
import java.util.List;
import java.util.Optional;

public record RenameHouseholdPayload(
    Optional<HouseholdAdministration> household, List<RenameHouseholdError> userErrors) {}
