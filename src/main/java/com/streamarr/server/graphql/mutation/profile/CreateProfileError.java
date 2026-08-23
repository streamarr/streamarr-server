package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/** The {@code CreateProfileError} union; record names are the schema type names DGS resolves by. */
public sealed interface CreateProfileError extends MutationError
    permits HouseholdNotFoundError,
        ProfileNameRequiredError,
        ProfileNameTakenError,
        EligibleManagerRequiredError,
        ManagerNotEligibleError,
        LocalManagerNotFoundError,
        MaximumAllowedRatingAgeInvalidError {}
