package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code ChangeProfileKindError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface ChangeProfileKindError extends MutationError
    permits ProfileNotFoundError, ReauthenticationRequiredError, HomeAnchorRequiredError {}
