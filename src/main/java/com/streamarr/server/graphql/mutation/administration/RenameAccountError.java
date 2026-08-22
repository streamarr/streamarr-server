package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code RenameAccountError} union; record names are the schema type names DGS resolves by. */
public sealed interface RenameAccountError extends MutationError
    permits AccountNotFoundError, DisplayNameRequiredError, InvalidIdError {}
