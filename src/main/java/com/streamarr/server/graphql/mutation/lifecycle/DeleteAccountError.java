package com.streamarr.server.graphql.mutation.lifecycle;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code DeleteAccountError} union; record names are the schema type names DGS resolves by. */
public sealed interface DeleteAccountError extends MutationError
    permits AccountNotFoundError,
        ReasonRequiredError,
        ReauthenticationRequiredError,
        FinalAccountError,
        LastHouseholdAdminError,
        LastServerAdminError,
        ReplacementManagerRequiredError,
        ReplacementManagerNotFoundError,
        ReplacementManagerNotEligibleError,
        HomeAnchorRequiredError,
        NoEligibleAdminError {}
