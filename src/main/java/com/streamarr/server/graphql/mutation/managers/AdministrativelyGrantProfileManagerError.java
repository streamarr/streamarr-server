package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code AdministrativelyGrantProfileManagerError} union; record names are the schema type
 * names DGS resolves by.
 */
public sealed interface AdministrativelyGrantProfileManagerError extends MutationError
    permits ProfileNotFoundError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        AccountNotFoundError,
        ProfileManagerNotEligibleError,
        AlreadyManagerError {}
