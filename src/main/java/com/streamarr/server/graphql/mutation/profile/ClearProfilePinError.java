package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code ClearProfilePinError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface ClearProfilePinError extends MutationError
    permits ProfileNotFoundError, WouldLockProfileError {}
