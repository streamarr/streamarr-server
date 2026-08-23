package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record ProfileNotFoundError(String message, List<String> inputPath)
    implements ChangeProfileKindError,
        RemoveProfileMaximumAllowedRatingAgeError,
        RemoveProfilePinError,
        DeleteProfileError,
        OverrideProfilePinError,
        RenameProfileError,
        SetProfileMaximumAllowedRatingAgeError,
        SetProfilePictureError,
        SetProfilePinError,
        InputMutationError {}
