package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface SetProfileMaximumAllowedRatingAgeError extends MutationError
    permits ProfileNotFoundError,
        ProfileManagersOnlyError,
        ReauthenticationRequiredError,
        ProfileRequiresEligibleManagerError,
        RestrictedAccountCannotAdministerError,
        RestrictedProfileRequiresHouseholdAdminError,
        MaximumAllowedRatingAgeInvalidError {}
