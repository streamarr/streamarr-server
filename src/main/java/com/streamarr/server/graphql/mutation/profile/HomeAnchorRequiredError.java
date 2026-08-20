package com.streamarr.server.graphql.mutation.profile;

public record HomeAnchorRequiredError(String message)
    implements ChangeProfileKindError,
        ClearProfileContentCeilingError,
        CreateProfileError,
        SetProfileContentCeilingError {}
