package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RejectProfileShareError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface RejectProfileShareError extends MutationError
    permits ShareNotFoundError, ShareNotPendingError {}
