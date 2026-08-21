package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code GrantHouseholdAdminError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface GrantHouseholdAdminError extends MutationError
    permits AccountNotFoundError, InvalidIdError, RestrictedAccountAuthorityError {}
