package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.ItemFailureReason;
import com.streamarr.server.domain.media.ItemOutcome;
import java.util.function.Function;
import lombok.NonNull;

public sealed interface MetadataFetchOutcome<T> {

  default <R> MetadataFetchOutcome<R> map(Function<? super T, ? extends R> mapper) {
    return switch (this) {
      case Found<T>(var result) -> new Found<>(mapper.apply(result));
      case NotFound<T> _ -> new NotFound<>();
      case Failed<T>(var cause) -> new Failed<>(cause);
    };
  }

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
