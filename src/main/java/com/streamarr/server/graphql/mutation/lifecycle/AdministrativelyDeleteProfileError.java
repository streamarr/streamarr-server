package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code AdministrativelyDeleteProfileError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface AdministrativelyDeleteProfileError extends MutationError
    permits ProfileNotFoundError,
        InvalidIdError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        ProfileBelongsToAccountError {}
