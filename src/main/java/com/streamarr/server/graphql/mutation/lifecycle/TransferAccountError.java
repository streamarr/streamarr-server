package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code TransferAccountError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface TransferAccountError extends MutationError
    permits AccountNotFoundError,
        HouseholdNotFoundError,
        SameHouseholdError,
        FinalAccountError,
        LastHouseholdAdminError,
        NoEligibleAdminError,
        ProfileNameTakenError,
        EligibleManagerRequiredError,
        RestrictedFirstAccountError {}
