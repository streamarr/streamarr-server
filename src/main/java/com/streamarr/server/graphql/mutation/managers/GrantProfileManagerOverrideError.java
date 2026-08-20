package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code GrantProfileManagerOverrideError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface GrantProfileManagerOverrideError extends MutationError
    permits ProfileNotFoundError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        RecipientNotFoundError,
        RecipientNotEligibleError,
        AlreadyManagerError {}
