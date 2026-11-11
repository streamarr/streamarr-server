package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code ChangeShareCodeLifetimeError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface ChangeShareCodeLifetimeError extends MutationError
    permits LifetimeOutOfRangeError {}
