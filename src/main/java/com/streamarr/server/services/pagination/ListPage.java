package com.streamarr.server.services.pagination;

import java.util.List;
import java.util.function.Function;
import lombok.NonNull;

/** One page of a list in list order, and whether rows exist on either side of it. */
public record ListPage<T>(
    @NonNull List<Row<T>> rows, boolean hasNextPage, boolean hasPreviousPage) {

  /** The same page with each item replaced, keeping every row's position. */
  public <U> ListPage<U> map(Function<T, U> mapper) {
    var mapped =
        rows.stream().map(row -> new Row<>(mapper.apply(row.item()), row.position())).toList();
    return new ListPage<>(mapped, hasNextPage, hasPreviousPage);
  }
}
