package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.PaginationOptions;
import graphql.relay.Connection;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import java.util.List;
import java.util.function.Function;

/** Relay paging over an ordered forward-keyset query fetched with a one-row lookahead. */
public final class KeysetConnections {

  private KeysetConnections() {}

  public static <T> Connection<T> page(
      List<T> fetched, Function<T, String> keyOf, PaginationOptions options) {
    var limit = options.getLimit();
    List<Edge<T>> edges =
        fetched.subList(0, Math.min(fetched.size(), limit)).stream()
            .<Edge<T>>map(
                item ->
                    new DefaultEdge<>(
                        item,
                        new DefaultConnectionCursor(ListConnections.encode(keyOf.apply(item)))))
            .toList();
    var pageInfo =
        new DefaultPageInfo(
            edges.isEmpty() ? null : edges.getFirst().getCursor(),
            edges.isEmpty() ? null : edges.getLast().getCursor(),
            options.getCursor().isPresent(),
            fetched.size() > limit);
    return new DefaultConnection<>(edges, pageInfo);
  }
}
