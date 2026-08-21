package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RevokeServerAdminError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface RevokeServerAdminError extends MutationError
    permits AccountNotFoundError,
        InvalidIdError,
        ReauthenticationRequiredError,
        ReasonRequiredError,
        LastServerAdminError {}
