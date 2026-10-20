package com.streamarr.server.fixtures;

import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.Optional;

public final class PaginationFixture {

  private PaginationFixture() {}

  public static PageRequest firstRows(int limit) {
    return PageRequest.builder()
        .position(Optional.empty())
        .direction(PaginationDirection.FORWARD)
        .limit(limit)
        .build();
  }

  /** A seek for a list's first rows, as a repository reads them for its first page. */
  public static Seek firstSeek(ListOrder order, int limit) {
    return Seek.builder()
        .order(order)
        .position(Optional.empty())
        .direction(PaginationDirection.FORWARD)
        .limit(limit)
        .build();
  }

  public static PageRequest rowsAfter(int limit, Row<?> row) {
    return PageRequest.builder()
        .position(Optional.of(row.position()))
        .direction(PaginationDirection.FORWARD)
        .limit(limit)
        .build();
  }

  public static PageRequest rowsBefore(int limit, Row<?> row) {
    return rowsFrom(limit, PaginationDirection.REVERSE, row);
  }

  public static PageRequest rowsFrom(int limit, PaginationDirection direction, Row<?> row) {
    return PageRequest.builder()
        .position(Optional.of(row.position()))
        .direction(direction)
        .limit(limit)
        .build();
  }
}
