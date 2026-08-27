package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RemoveProfileManagerOverrideError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface RemoveProfileManagerOverrideError extends MutationError
    permits ProfileNotFoundError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        NotAManagerError,
        ProfileRequiresEligibleManagerError {}
