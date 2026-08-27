package com.streamarr.server.graphql.mutation.credentials;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code IssueAccountInvitationError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface IssueAccountInvitationError extends MutationError
    permits EmailRequiredError,
        EmailInvalidError,
        EmailAlreadyUsedError,
        ProfileNameRequiredError,
        ProfileNameTakenError,
        HouseholdNotFoundError,
        RestrictedFirstAccountError,
        RestrictedHouseholdAdminError,
        EligibleProfileManagerRequiredError,
        MaximumAllowedRatingAgeInvalidError,
        ProfileManagerNotEligibleError {}
