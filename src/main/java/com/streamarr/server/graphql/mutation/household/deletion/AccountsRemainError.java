package com.streamarr.server.graphql.mutation.household.deletion;

public record AccountsRemainError(String message)
    implements DeleteEmptyHouseholdError,
        TransferLastAccountAndDeleteHouseholdError,
        DeleteLastAccountAndHouseholdError,
        DeleteLastAccountAndHouseholdPreservingPersonalProfileError {}
