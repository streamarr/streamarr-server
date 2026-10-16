package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.graphql.mutation.MutationError;

public sealed interface StopLibraryScanError extends MutationError
    permits LibraryNotFoundError, LibraryScanNotInProgressError, InvalidIdError {}
