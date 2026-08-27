package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code EndProfileShareError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface EndProfileShareError extends MutationError
    permits ShareNotFoundError, ShareNotActiveError, MembershipShareCannotEndError {}
