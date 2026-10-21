package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.Position;
import java.util.function.Function;

/**
 * The cursor identity and edge order of a Library's items under one filter, shared by the items
 * connection and the edges its subscriptions deliver.
 */
public final class LibraryItemCursors {

  private static final String LIST = "library-items";

  private LibraryItemCursors() {}

  public static ListIdentity listOf(MediaFilter filter) {
    return new ListIdentity(LIST, filter.listScope());
  }

  public static Function<Position, MediaOrder> orderOf(MediaFilter filter) {
    return position -> MediaOrder.at(filter.getSortBy(), filter.getSortDirection(), position);
  }
}
