package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RevokeHouseholdAdminError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface RevokeHouseholdAdminError extends MutationError
    permits AccountNotFoundError, InvalidIdError, LastHouseholdAdminError {}
