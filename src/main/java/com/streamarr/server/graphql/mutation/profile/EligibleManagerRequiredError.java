package com.streamarr.server.graphql.mutation.profile;

public record EligibleManagerRequiredError(String message)
    implements ChangeProfileKindError,
        ClearProfileContentCeilingError,
        CreateProfileError,
        SetProfileContentCeilingError {}
