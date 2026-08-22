package com.streamarr.server.graphql.mutation.profile;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record MaximumAllowedRatingAgeInvalidError(String message, List<String> inputPath)
    implements ChangeProfileKindError,
        ClearProfileContentCeilingError,
        CreateProfileError,
        SetProfileContentCeilingError,
        InputMutationError {}
