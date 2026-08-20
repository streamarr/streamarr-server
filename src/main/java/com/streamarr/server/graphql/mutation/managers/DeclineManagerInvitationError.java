package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code DeclineManagerInvitationError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface DeclineManagerInvitationError extends MutationError
    permits ManagerInvitationNotFoundError {}
