package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code DeleteProfileError} union; record names are the schema type names DGS resolves by. */
public sealed interface DeleteProfileError extends MutationError
    permits ProfileNotFoundError, ProfileNotDeletableError, ReauthenticationRequiredError {}
