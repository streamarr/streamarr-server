package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.dto.HouseholdAdministration;
import java.util.List;

public record RenameHouseholdPayload(
    HouseholdAdministration household, List<RenameHouseholdError> userErrors) {}
