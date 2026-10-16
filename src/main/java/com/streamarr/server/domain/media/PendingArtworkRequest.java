package com.streamarr.server.domain.media;

import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * A target's pending artwork request: its generation, and the library operation that waits for it,
 * if any.
 */
public record PendingArtworkRequest(long generation, @NonNull Optional<UUID> libraryOperationId) {}
