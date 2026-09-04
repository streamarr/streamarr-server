package com.streamarr.server.graphql.mutation.identity.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code AdministrativelyDeleteAccountError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface AdministrativelyDeleteAccountError extends MutationError
    permits AccountNotFoundError,
        InvalidIdError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        LastHouseholdAccountError,
        LastHouseholdAdminError,
        LastServerAdminError,
        ReplacementManagerRequiredError,
        ProfileManagerNotEligibleError,
        ProfileRequiresEligibleManagerError,
        RestrictedProfileRequiresHouseholdAdminError {}
