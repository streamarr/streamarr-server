package com.streamarr.server.graphql.mutation.household.deletion;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface DeleteLastAccountAndHouseholdError extends MutationError
    permits HouseholdNotFoundError,
        InvalidIdError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        AccountsRemainError,
        LastAccountNotFoundError,
        LastServerAdminError {}
