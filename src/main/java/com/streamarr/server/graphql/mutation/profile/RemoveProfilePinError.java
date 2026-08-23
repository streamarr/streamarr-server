package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RemoveProfilePinError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface RemoveProfilePinError extends MutationError
    permits ProfileNotFoundError, WouldLockProfileError {}
