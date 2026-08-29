package com.streamarr.server.graphql.mutation.managers;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code AcceptManagerInvitationError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface AcceptManagerInvitationError extends MutationError
    permits ManagerInvitationNotFoundError, ProfileManagerNotEligibleError, AlreadyManagerError {}
