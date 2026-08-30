package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code UnblockEsnError} union; record names are the schema type names DGS resolves by. */
public sealed interface UnblockEsnError extends MutationError
    permits HouseholdNotFoundError, EsnRequiredError, EsnInvalidError, EsnBlockNotFoundError {}
