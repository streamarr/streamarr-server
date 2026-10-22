package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.InputMutationError;
import java.util.List;

public record LibraryNotFoundError(String message, List<String> inputPath)
    implements StartLibraryScanError,
        StartLibraryRefreshError,
        StopLibraryScanError,
        StopLibraryRefreshError,
        RemoveLibraryError,
        InputMutationError {}
