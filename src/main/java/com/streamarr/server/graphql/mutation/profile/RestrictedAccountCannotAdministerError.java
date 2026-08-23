package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record RestrictedAccountCannotAdministerError(String message, List<String> inputPath)
    implements ChangeProfileKindError,
        RemoveProfileMaximumAllowedRatingAgeError,
        SetProfileMaximumAllowedRatingAgeError,
        InputMutationError {}
