package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.Library;
import java.util.List;
import java.util.Optional;

public record StartLibraryRefreshPayload(
    Optional<Library> library, List<StartLibraryRefreshError> userErrors) {}
