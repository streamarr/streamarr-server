package com.streamarr.server.graphql.cursor;

import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
public class MediaPaginationOptions {
  private final UUID cursorId;

  private final PaginationOptions paginationOptions;

  private final MediaFilter mediaFilter;

  public Optional<UUID> getCursorId() {
    return Optional.ofNullable(cursorId);
  }
}
