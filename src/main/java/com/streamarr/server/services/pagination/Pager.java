package com.streamarr.server.services.pagination;

import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads pages of any {@link PagedList}. A page reads one row past its limit to learn whether more
 * follow. A request that continues from a position also reads the row at that position, which
 * proves rows precede the page; when that item has left the list, one row read the other way
 * decides instead. A removed item's position still separates the rows before it from the rows after
 * it.
 */
public final class Pager {

  private Pager() {}

  /**
   * @throws InvalidPaginationCursorException when the request's position cannot belong to the list
   */
  public static <T, S> ListPage<T> page(PagedList<T, S> list, S scope, PageRequest request) {
    return pages(list, Map.of(scope, request)).get(scope);
  }

  /**
   * Pages several scopes of one list together: one read for every scope, and one more for the
   * scopes whose position no longer holds a row.
   *
   * @throws InvalidPaginationCursorException when a request's position cannot belong to the list
   */
  public static <T, S> Map<S, ListPage<T>> pages(
      PagedList<T, S> list, Map<S, PageRequest> requests) {
    var reads = new LinkedHashMap<S, Seek>();
    requests.forEach((scope, request) -> reads.put(scope, pageSeek(list.order(scope), request)));
    var rowsByScope = list.rowsBeyond(reads);
    var behind = new LinkedHashMap<S, Seek>();
    requests.forEach(
        (scope, request) -> {
          if (needsReadBehind(request, rowsByScope.get(scope))) {
            behind.put(scope, behindSeek(reads.get(scope).order(), request));
          }
        });
    var rowsBehind = behind.isEmpty() ? Map.<S, List<Row<T>>>of() : list.rowsBeyond(behind);
    var pages = new LinkedHashMap<S, ListPage<T>>();
    requests.forEach(
        (scope, request) -> {
          var read = rowsByScope.get(scope);
          var hasRowsBehind =
              startsAtPosition(request, read)
                  || !rowsBehind.getOrDefault(scope, List.of()).isEmpty();
          pages.put(scope, assemble(request, read, hasRowsBehind));
        });
    return pages;
  }

  private static Seek pageSeek(ListOrder order, PageRequest request) {
    if (request.position().filter(position -> !order.admits(position.value())).isPresent()) {
      throw new InvalidPaginationCursorException("Cursor does not belong to this list.");
    }

    return Seek.builder()
        .order(order)
        .position(request.position())
        .direction(request.direction())
        .limit(request.limit() + 2)
        .includingPosition(true)
        .build();
  }

  private static Seek behindSeek(ListOrder order, PageRequest request) {
    return Seek.builder()
        .order(order)
        .position(request.position())
        .direction(opposite(request.direction()))
        .limit(1)
        .build();
  }

  // A continued page that read its position's row knows rows precede it; otherwise one row read
  // the other way decides.
  private static <T> boolean needsReadBehind(PageRequest request, List<Row<T>> read) {
    return request.position().isPresent() && !startsAtPosition(request, read);
  }

  private static <T> boolean startsAtPosition(PageRequest request, List<Row<T>> read) {
    return !read.isEmpty() && request.position().equals(Optional.of(read.getFirst().position()));
  }

  private static <T> ListPage<T> assemble(
      PageRequest request, List<Row<T>> read, boolean hasRowsBehind) {
    var beyond = startsAtPosition(request, read) ? read.subList(1, read.size()) : read;
    var hasMoreAhead = beyond.size() > request.limit();
    var nearest = beyond.subList(0, Math.min(beyond.size(), request.limit()));
    if (request.direction() == PaginationDirection.FORWARD) {
      return new ListPage<>(nearest, hasMoreAhead, hasRowsBehind);
    }

    return new ListPage<>(List.copyOf(nearest.reversed()), hasRowsBehind, hasMoreAhead);
  }

  private static PaginationDirection opposite(PaginationDirection direction) {
    return switch (direction) {
      case FORWARD -> PaginationDirection.REVERSE;
      case REVERSE -> PaginationDirection.FORWARD;
    };
  }
}
