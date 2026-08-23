package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

/**
 * The {@code ClearProfileContentCeilingError} union; record names are the schema type names DGS
 * resolves by.
 */
public sealed interface ClearProfileContentCeilingError extends MutationError
    permits ProfileNotFoundError,
        ReauthenticationRequiredError,
        EligibleManagerRequiredError,
        RestrictedAccountAuthorityError,
        MaximumAllowedRatingAgeInvalidError {}
