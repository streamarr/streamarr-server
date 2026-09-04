package com.streamarr.server.graphql.mutation.identity.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code TransferAccountError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface TransferAccountError extends MutationError
    permits AccountNotFoundError,
        InvalidIdError,
        HouseholdNotFoundError,
        SameHouseholdError,
        LastHouseholdAccountError,
        LastHouseholdAdminError,
        RestrictedProfileRequiresHouseholdAdminError,
        ProfileNameTakenError,
        ProfileRequiresEligibleManagerError,
        RestrictedFirstAccountError {}
