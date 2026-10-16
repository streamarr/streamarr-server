package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface StartLibraryRefreshError extends MutationError
    permits LibraryNotFoundError, LibraryBusyError, InvalidIdError {}
