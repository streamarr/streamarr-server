package com.streamarr.server.graphql.mutation.household.deletion;

public record LastAccountNotFoundError(String message)
    implements TransferLastAccountAndDeleteHouseholdError,
        DeleteLastAccountAndHouseholdError,
        DeleteLastAccountAndHouseholdPreservingPersonalProfileError {}
