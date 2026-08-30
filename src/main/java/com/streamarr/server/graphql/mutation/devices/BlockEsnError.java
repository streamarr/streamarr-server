package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code BlockEsnError} union; record names are the schema type names DGS resolves by. */
public sealed interface BlockEsnError extends MutationError
    permits HouseholdNotFoundError,
        EsnRequiredError,
        EsnInvalidError,
        ReasonRequiredError,
        EsnAlreadyBlockedError {}
