package com.streamarr.server.services.pagination;

import java.util.List;

/**
 * Reads one page of any {@link PagedList}. A page reads one row past its limit to learn whether
 * more follow, and a request that continues from a position reads one row the other way to learn
 * whether rows precede it. The position need not still hold a row: a removed item's position still
 * separates the rows before it from the rows after it.
 */
public final class Pager {

  private Pager() {}

  public static <T, S> ListPage<T> page(PagedList<T, S> list, S scope, PageRequest request) {
    var read =
        list.rowsBeyond(
            scope,
            Seek.builder()
                .position(request.position())
                .direction(request.direction())
                .limit(request.limit() + 1)
                .build());
    var hasMoreAhead = read.size() > request.limit();
    var nearest = read.subList(0, Math.min(read.size(), request.limit()));
    var hasRowsBehind = hasRowsBehind(list, scope, request);
    if (request.direction() == PaginationDirection.FORWARD) {
      return new ListPage<>(nearest, hasMoreAhead, hasRowsBehind);
    }

    return new ListPage<>(List.copyOf(nearest.reversed()), hasRowsBehind, hasMoreAhead);
  }

  private static <T, S> boolean hasRowsBehind(PagedList<T, S> list, S scope, PageRequest request) {
    if (request.position().isEmpty()) {
      return false;
    }

    var behind =
        Seek.builder()
            .position(request.position())
            .direction(opposite(request.direction()))
            .limit(1)
            .includingPosition(true)
            .build();
    return !list.rowsBeyond(scope, behind).isEmpty();
  }

  private static PaginationDirection opposite(PaginationDirection direction) {
    return switch (direction) {
      case FORWARD -> PaginationDirection.REVERSE;
      case REVERSE -> PaginationDirection.FORWARD;
    };
  }
}
