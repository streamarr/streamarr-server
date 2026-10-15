package com.streamarr.server.services;

import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

/**
 * What the completion of a target's artwork steps compares with its attempt: the pending request's
 * generation, and the library operation that waits for the request, if any.
 */
public record PendingArtworkRequest(long generation, @NonNull Optional<UUID> libraryOperationId) {}
