package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code RenameHouseholdError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface RenameHouseholdError extends MutationError
    permits HouseholdNotFoundError, HouseholdNameRequiredError {}
