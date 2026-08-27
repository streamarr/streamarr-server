package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code AdministrativelyEndProfileShareError} union; record names are the schema type names
 * DGS resolves by.
 */
public sealed interface AdministrativelyEndProfileShareError extends MutationError
    permits ShareNotFoundError,
        ShareNotActiveError,
        MembershipShareCannotEndError,
        ReasonRequiredError,
        ReauthenticationRequiredError {}
