package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code RenameProfileError} union; record names are the schema type names DGS resolves by. */
public sealed interface RenameProfileError extends MutationError
    permits ProfileNotFoundError, ProfileNameRequiredError, ProfileNameTakenError {}
