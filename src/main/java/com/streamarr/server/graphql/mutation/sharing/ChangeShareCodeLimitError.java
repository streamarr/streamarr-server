package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code ChangeShareCodeLimitError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface ChangeShareCodeLimitError extends MutationError
    permits ShareCodeLimitOutOfRangeError {}
