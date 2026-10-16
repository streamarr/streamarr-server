package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record InvalidIdError(String message, List<String> inputPath)
    implements StartLibraryScanError,
        StartLibraryRefreshError,
        RemoveLibraryError,
        InputMutationError {}
