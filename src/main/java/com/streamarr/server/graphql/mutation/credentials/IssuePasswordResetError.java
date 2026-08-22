package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code IssuePasswordResetError} union; record names are the schema type names DGS resolves
 * by.
 */
public sealed interface IssuePasswordResetError extends MutationError
    permits AccountNotFoundError, ReasonRequiredError, ReauthenticationRequiredError {}
