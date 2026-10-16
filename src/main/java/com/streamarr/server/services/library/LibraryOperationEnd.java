package com.streamarr.server.services.library;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/** The end of a library operation that an execution concluded. */
@Builder
public record LibraryOperationEnd(
    @NonNull UUID libraryOperationId,
    @NonNull LibraryOperationConclusion conclusion,
    @NonNull Instant endedAt) {}
