package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code OfferProfileShareError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface OfferProfileShareError extends MutationError
    permits ProfileNotFoundError, HouseholdNotFoundError, ProfileAlreadySharedError {}
