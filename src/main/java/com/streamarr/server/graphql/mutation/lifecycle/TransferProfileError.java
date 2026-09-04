package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code TransferProfileError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface TransferProfileError extends MutationError
    permits ProfileNotFoundError,
        InvalidIdError,
        HouseholdNotFoundError,
        SameHouseholdError,
        ProfileBelongsToAccountError,
        EligibleProfileManagerRequiredError,
        AccountNotFoundError,
        ProfileManagerNotEligibleError,
        ProfileNameTakenError,
        RestrictedProfileRequiresHouseholdAdminError {}
