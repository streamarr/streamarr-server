package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code SetProfilePinError} union; record names are the schema type names DGS resolves by. */
public sealed interface SetProfilePinError extends MutationError
    permits ProfileNotFoundError, PinMalformedError {}
