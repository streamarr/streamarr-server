package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface StopLibraryRefreshError extends MutationError
    permits LibraryNotFoundError, LibraryRefreshNotInProgressError, InvalidIdError {}
