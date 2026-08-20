package com.streamarr.server.graphql.mutation.devices;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code UnblockEsnServerWideError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface UnblockEsnServerWideError extends MutationError
    permits EsnRequiredError, EsnBlockNotFoundError {}
