package com.streamarr.server.graphql.mutation.administration;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code CreateHouseholdError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface CreateHouseholdError extends MutationError
    permits HouseholdNameRequiredError {}
