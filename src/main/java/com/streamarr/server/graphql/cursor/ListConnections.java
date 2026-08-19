package com.streamarr.server.graphql.cursor;

import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import graphql.relay.Connection;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * Relay keyset paging over an already-ordered, bounded list (a Profile picker, the Households an
 * Account may use). The cursor is the opaque key of an element; paging seeks past it — never an
 * offset — so a client that keeps paging while {@code hasNextPage} is true sees every element
 * exactly once even when the list changes under it.
 */
public final class ListConnections {

  private ListConnections() {}

  public static <T> Connection<T> page(
      List<T> ordered, Function<T, String> keyOf, PaginationOptions options) {
    var keys = ordered.stream().map(keyOf).toList();
    var anchor = options.getCursor().map(ListConnections::decode).map(keys::indexOf).orElse(-1);
    var limit = options.getLimit();

    int from;
    int to;
    if (options.getPaginationDirection() == PaginationDirection.REVERSE) {
      to = options.getCursor().isPresent() && anchor >= 0 ? anchor : ordered.size();
      from = Math.max(0, to - limit);
    } else {
      from = anchor + 1;
      to = Math.min(ordered.size(), from + limit);
    }

    var edges = new ArrayList<Edge<T>>();
    for (var index = from; index < to; index++) {
      edges.add(
          new DefaultEdge<>(
              ordered.get(index), new DefaultConnectionCursor(encode(keys.get(index)))));
    }
    var pageInfo =
        new DefaultPageInfo(
            edges.isEmpty() ? null : edges.getFirst().getCursor(),
            edges.isEmpty() ? null : edges.getLast().getCursor(),
            from > 0,
            to < ordered.size());
    return new DefaultConnection<>(edges, pageInfo);
  }

  static String encode(String key) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(key.getBytes(StandardCharsets.UTF_8));
  }

  static String decode(String cursor) {
    try {
      return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException _) {
      throw new InvalidCursorException("Cursor is not valid.");
    }
  }
}
