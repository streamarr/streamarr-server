package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code BlockEsnServerWideError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface BlockEsnServerWideError extends MutationError
    permits EsnRequiredError,
        ReasonRequiredError,
        EsnAlreadyBlockedError,
        ReauthenticationRequiredError {}
