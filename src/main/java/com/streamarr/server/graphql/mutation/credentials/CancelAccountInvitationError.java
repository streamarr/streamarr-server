package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code CancelAccountInvitationError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface CancelAccountInvitationError extends MutationError
    permits InvitationNotPendingError {}
