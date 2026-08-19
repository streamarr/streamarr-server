package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.HouseholdAdministration;
import java.util.List;

public record CreateHouseholdPayload(
    HouseholdAdministration household, List<CreateHouseholdError> userErrors) {}
