package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code AcceptProfileShareError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface AcceptProfileShareError extends MutationError
    permits ShareNotFoundError,
        ShareNotPendingError,
        OfferInvalidatedError,
        RestrictedProfileRequiresHouseholdAdminError,
        ShareNameConflictError {}
