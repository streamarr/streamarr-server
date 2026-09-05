package com.streamarr.server.graphql.mutation.household.deletion;

public record LastServerAdminError(String message)
    implements DeleteLastAccountAndHouseholdError,
        DeleteLastAccountAndHouseholdPreservingPersonalProfileError {}
