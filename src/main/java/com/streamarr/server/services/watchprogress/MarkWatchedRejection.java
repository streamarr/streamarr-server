package com.streamarr.server.services.watchprogress;

import java.util.UUID;
import lombok.NonNull;

/** Every expected reason {@code markWatched} refuses; each maps to one schema error type. */
public sealed interface MarkWatchedRejection {

  record ItemNotFound(@NonNull UUID itemId) implements MarkWatchedRejection {}
}
