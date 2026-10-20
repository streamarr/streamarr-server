package com.streamarr.server.services.pagination;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A list the server orders and pages. An implementation reads rows past a position in its own
 * order; {@link Pager} turns those reads into pages, flags and cursor positions.
 *
 * @param <T> the listed item
 * @param <S> the scope the list is read in, such as a Profile
 */
public interface PagedList<T, S> {

  ListOrder order(S scope);

  /**
   * Up to {@code seek.limit()} rows beyond the seek's position in its direction, nearest first.
   * With no position, reading starts at that direction's end of the list.
   */
  List<Row<T>> rowsBeyond(S scope, Seek seek);

  /**
   * The rows beyond each scope's seek, keyed by scope. A list that can read several scopes in one
   * query overrides this; by default each scope is read on its own.
   */
  default Map<S, List<Row<T>>> rowsBeyond(Map<S, Seek> seeks) {
    var rows = new LinkedHashMap<S, List<Row<T>>>();
    seeks.forEach((scope, seek) -> rows.put(scope, rowsBeyond(scope, seek)));
    return rows;
  }
}
