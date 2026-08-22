package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code OverrideProfilePinError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface OverrideProfilePinError extends MutationError
    permits ProfileNotFoundError,
        PinMalformedError,
        ReasonRequiredError,
        ReauthenticationRequiredError {}
