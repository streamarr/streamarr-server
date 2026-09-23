package com.streamarr.server.services.metadata;

import com.streamarr.server.domain.media.ItemFailureReason;
import lombok.NonNull;

public sealed interface MetadataSearchOutcome {

  record Found(@NonNull RemoteSearchResult result) implements MetadataSearchOutcome {}

  record NotFound() implements MetadataSearchOutcome {}

  record TemporarilyUnavailable(@NonNull Throwable cause) implements MetadataSearchOutcome {

    public ItemFailureReason reason() {
      return MetadataFailureReasons.of(cause);
    }
  }
}
