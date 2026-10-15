package com.streamarr.server.graphql.mutation.library;

import java.util.List;
import java.util.Optional;

public record RemoveLibraryPayload(
    Optional<String> removedLibraryId, List<RemoveLibraryError> userErrors) {}
