package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code DeleteMyAccountError} union; record names are the schema type names DGS resolves by.
 */
public sealed interface DeleteMyAccountError extends MutationError
    permits ConfirmationRequiredError,
        ReauthenticationRequiredError,
        FinalAccountError,
        LastHouseholdAdminError,
        LastServerAdminError,
        HomeAnchorRequiredError,
        NoEligibleAdminError {}
