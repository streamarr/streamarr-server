package com.streamarr.server.services.pagination;

import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KeysetPaginationOptions {

  private final UUID cursorId;
  private final PaginationOptions paginationOptions;

  public Optional<UUID> getCursorId() {
    return Optional.ofNullable(cursorId);
  }
}
