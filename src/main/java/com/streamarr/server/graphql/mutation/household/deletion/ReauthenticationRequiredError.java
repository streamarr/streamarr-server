package com.streamarr.server.graphql.mutation.household.deletion;

public record ReauthenticationRequiredError(String message)
    implements DeleteEmptyHouseholdError,
        TransferLastAccountAndDeleteHouseholdError,
        DeleteLastAccountAndHouseholdError,
        DeleteLastAccountAndHouseholdPreservingPersonalProfileError {}
