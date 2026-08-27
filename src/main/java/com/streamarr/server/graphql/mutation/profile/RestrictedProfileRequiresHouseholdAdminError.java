package com.streamarr.server.graphql.mutation.profile;

public record RestrictedProfileRequiresHouseholdAdminError(String message)
    implements ChangeProfileKindError,
        SetProfileMaximumAllowedRatingAgeError,
        RemoveProfileMaximumAllowedRatingAgeError {}
