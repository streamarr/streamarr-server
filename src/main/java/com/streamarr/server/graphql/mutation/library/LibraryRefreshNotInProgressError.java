package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.LibraryStatus;

public record LibraryRefreshNotInProgressError(String message, LibraryStatus status)
    implements StopLibraryRefreshError {}
