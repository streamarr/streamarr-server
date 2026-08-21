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
 * offset. The anchor must remain in the list because the key alone does not carry the list's sort
 * values; a disappeared anchor is rejected instead of silently restarting and repeating items.
 */
public final class ListConnections {

  private ListConnections() {}

  public static <T> Connection<T> page(
      List<T> ordered, Function<T, String> keyOf, PaginationOptions options) {
    var keys = ordered.stream().map(keyOf).toList();
    var cursorKey = options.getCursor().map(ListConnections::decode);
    var anchor = cursorKey.map(keys::indexOf).orElse(-1);
    if (cursorKey.isPresent() && anchor < 0) {
      throw new InvalidCursorException("Cursor no longer identifies an item.");
    }
    var window = window(options, anchor, ordered.size());
    var from = window.from();
    var to = window.to();

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

  private static Window window(PaginationOptions options, int anchor, int itemCount) {
    var limit = options.getLimit();
    if (options.getPaginationDirection() == PaginationDirection.REVERSE) {
      var to = options.getCursor().isPresent() && anchor >= 0 ? anchor : itemCount;
      return new Window(Math.max(0, to - limit), to);
    }
    var from = anchor + 1;
    return new Window(from, Math.min(itemCount, from + limit));
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

  private record Window(int from, int to) {}
}
