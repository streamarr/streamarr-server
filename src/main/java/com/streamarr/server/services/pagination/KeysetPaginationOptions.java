package com.streamarr.server.services.pagination;

import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;

public record KeysetPaginationOptions(UUID cursorId, @NonNull PaginationOptions paginationOptions) {

  public Optional<UUID> getCursorId() {
    return Optional.ofNullable(cursorId);
  }

  public PaginationOptions getPaginationOptions() {
    return paginationOptions;
  }
}
