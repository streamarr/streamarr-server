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
        LastAccountActionRequiredError,
        LastAccountActionNotAllowedError,
        DestinationRequiredError,
        DestinationNotFoundError,
        ReplacementManagerRequiredError,
        AccountNotFoundError,
        ProfileManagerNotEligibleError,
        LastServerAdminError {}
