package com.streamarr.server.graphql.mutation.household.deletion;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface DeleteEmptyHouseholdError extends MutationError
    permits HouseholdNotFoundError,
        InvalidIdError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        AccountsRemainError {}
