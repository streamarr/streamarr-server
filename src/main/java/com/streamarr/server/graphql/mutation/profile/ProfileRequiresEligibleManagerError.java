package com.streamarr.server.graphql.mutation.profile;

public record ProfileRequiresEligibleManagerError(String message)
    implements ChangeProfileKindError,
        RemoveProfileMaximumAllowedRatingAgeError,
        CreateProfileError,
        SetProfileMaximumAllowedRatingAgeError {}
