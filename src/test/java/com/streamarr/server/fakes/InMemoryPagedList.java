package com.streamarr.server.fakes;

import com.streamarr.server.services.pagination.ListOrder;
import com.streamarr.server.services.pagination.PagedList;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.Seek;
import java.util.List;

/** Fixed rows of one scope, read the way a Keyset seek reads them in PostgreSQL. */
public record InMemoryPagedList<T, S>(ListOrder order, List<Row<T>> rows)
    implements PagedList<T, S> {

  @Override
  public ListOrder order(S scope) {
    return order;
  }

  @Override
  public List<Row<T>> rowsBeyond(S scope, Seek seek) {
    return InMemoryKeyset.rowsBeyond(rows, seek);
  }
}
