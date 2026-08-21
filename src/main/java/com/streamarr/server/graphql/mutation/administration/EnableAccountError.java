package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code EnableAccountError} union; record names are the schema type names DGS resolves by. */
public sealed interface EnableAccountError extends MutationError
    permits AccountNotFoundError, InvalidIdError {}
