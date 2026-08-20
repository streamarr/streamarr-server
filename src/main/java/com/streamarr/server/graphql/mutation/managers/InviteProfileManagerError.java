package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code InviteProfileManagerError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface InviteProfileManagerError extends MutationError
    permits ProfileNotFoundError,
        RecipientNotFoundError,
        RecipientNotEligibleError,
        AlreadyManagerError {}
