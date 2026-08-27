package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code CancelProfileShareError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface CancelProfileShareError extends MutationError
    permits ShareNotFoundError, ShareNotPendingError {}
