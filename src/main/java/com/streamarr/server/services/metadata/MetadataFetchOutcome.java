package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import lombok.NonNull;

public sealed interface MetadataFetchOutcome<T> {

  record Found<T>(@NonNull T result) implements MetadataFetchOutcome<T> {}

  record NotFound<T>() implements MetadataFetchOutcome<T> {}

  record Failed<T>(@NonNull Throwable cause) implements MetadataFetchOutcome<T> {

    public ItemFailureReason reason() {
      return MetadataFailureReasons.of(cause);
    }

    public ItemOutcome.Failed toItemOutcome() {
      return ItemOutcome.Failed.of(reason(), cause);
    }
  }
}
