package com.streamarr.server.graphql.mutation.sharing;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code IssueShareCodeError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface IssueShareCodeError extends MutationError permits HouseholdNotFoundError {}
