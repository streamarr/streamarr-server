package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code GrantServerAdminError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface GrantServerAdminError extends MutationError
    permits AccountNotFoundError,
        ReauthenticationRequiredError,
        ReasonRequiredError,
        RestrictedAccountAuthorityError {}
