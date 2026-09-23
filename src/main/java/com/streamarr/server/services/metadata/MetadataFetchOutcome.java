package com.streamarr.server.services.metadata;

import lombok.NonNull;

public sealed interface MetadataFetchOutcome<T> {

  record Found<T>(@NonNull T result) implements MetadataFetchOutcome<T> {}

  record NotFound<T>() implements MetadataFetchOutcome<T> {}

  record Failed<T>(@NonNull Throwable cause) implements MetadataFetchOutcome<T> {}
}
