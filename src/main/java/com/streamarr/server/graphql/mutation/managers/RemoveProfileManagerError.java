package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RemoveProfileManagerError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface RemoveProfileManagerError extends MutationError
    permits ProfileNotFoundError, NotAManagerError, ProfileRequiresEligibleManagerError {}
