package com.streamarr.server.services.pagination;

import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;

@Getter
@EqualsAndHashCode
@Builder(toBuilder = true)
public class KeysetPaginationOptions {

  private final UUID cursorId;

  @NonNull private final PaginationOptions paginationOptions;

  public Optional<UUID> getCursorId() {
    return Optional.ofNullable(cursorId);
  }
}
