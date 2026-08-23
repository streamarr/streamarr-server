package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code ForceDeleteProfileError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface ForceDeleteProfileError extends MutationError
    permits ProfileNotFoundError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        ProfileBelongsToAccountError {}
