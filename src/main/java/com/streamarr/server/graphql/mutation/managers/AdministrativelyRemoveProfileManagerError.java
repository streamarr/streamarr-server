package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code AdministrativelyRemoveProfileManagerError} union; record names are the schema type
 * names DGS resolves by.
 */
public sealed interface AdministrativelyRemoveProfileManagerError extends MutationError
    permits ProfileNotFoundError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        NotAManagerError,
        ProfileRequiresEligibleManagerError {}
