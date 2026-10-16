package com.streamarr.server.services.watchprogress;

import java.util.UUID;
import lombok.NonNull;

/** Every expected reason {@code markUnwatched} refuses; each maps to one schema error type. */
public sealed interface MarkUnwatchedRejection {

  record ItemNotFound(@NonNull UUID itemId) implements MarkUnwatchedRejection {}
}
