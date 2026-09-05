package com.streamarr.server.graphql.mutation.household.deletion;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface TransferLastAccountAndDeleteHouseholdError extends MutationError
    permits HouseholdNotFoundError,
        InvalidIdError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        AccountsRemainError,
        LastAccountNotFoundError,
        DestinationNotFoundError {}
