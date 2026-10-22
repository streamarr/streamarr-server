package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.LibraryStatus;

public record LibraryScanNotInProgressError(String message, LibraryStatus status)
    implements StopLibraryScanError {}
