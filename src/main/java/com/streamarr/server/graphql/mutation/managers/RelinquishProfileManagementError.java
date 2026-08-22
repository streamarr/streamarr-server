package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RelinquishProfileManagementError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface RelinquishProfileManagementError extends MutationError
    permits ProfileNotFoundError, ManagementAlreadyRemovedError, ManagerAnchorRequiredError {}
