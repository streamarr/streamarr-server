package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.LibraryStatus;

public record LibraryBusyError(String message, LibraryStatus status)
    implements StartLibraryScanError, StartLibraryRefreshError, RemoveLibraryError {}
