package com.streamarr.server.services.metadata;

import lombok.NonNull;

public sealed interface MetadataSearchOutcome {

  record Found(@NonNull RemoteSearchResult result) implements MetadataSearchOutcome {}

  record NotFound() implements MetadataSearchOutcome {}

  record TemporarilyUnavailable(@NonNull Throwable cause) implements MetadataSearchOutcome {}
}
