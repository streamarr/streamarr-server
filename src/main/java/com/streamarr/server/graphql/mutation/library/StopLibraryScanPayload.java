package com.streamarr.server.graphql.mutation.library;

import com.streamarr.server.domain.Library;
import java.util.List;
import java.util.Optional;

public record StopLibraryScanPayload(
    Optional<Library> library, List<StopLibraryScanError> userErrors) {}
