package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.Library;
import java.util.List;
import java.util.Optional;

public record StopLibraryRefreshPayload(
    Optional<Library> library, List<StopLibraryRefreshError> userErrors) {}
