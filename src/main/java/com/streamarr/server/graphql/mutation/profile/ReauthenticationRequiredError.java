package com.streamarr.server.graphql.mutation.profile;

public record ReauthenticationRequiredError(String message)
    implements ChangeProfileKindError,
        ClearProfileContentCeilingError,
        DeleteProfileError,
        OverrideProfilePinError,
        SetProfileContentCeilingError {}
