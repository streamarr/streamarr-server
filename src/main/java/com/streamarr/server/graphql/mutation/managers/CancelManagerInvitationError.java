package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code CancelManagerInvitationError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface CancelManagerInvitationError extends MutationError
    permits ManagerInvitationNotFoundError, InvitationNotPendingError {}
