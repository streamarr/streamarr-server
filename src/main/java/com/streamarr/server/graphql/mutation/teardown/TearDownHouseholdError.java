package com.streamarr.server.graphql.mutation.teardown;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code TearDownHouseholdError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface TearDownHouseholdError extends MutationError
    permits HouseholdNotFoundError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        AccountsRemainError,
        FinalAccountRequiredError,
        FinalAccountUnexpectedError,
        DestinationRequiredError,
        DestinationNotFoundError,
        ReplacementManagerRequiredError,
        ReplacementManagerNotFoundError,
        ReplacementManagerNotEligibleError,
        LastServerAdminError {}
