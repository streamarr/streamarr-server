package com.streamarr.server.graphql.dto;

import graphql.relay.ConnectionCursor;
import graphql.relay.Edge;
import lombok.NonNull;

/** An edge of a server-ordered list whose order key needs no further read to encode. */
public record KeyedEdge<T>(
    @NonNull T node, @NonNull ConnectionCursor cursor, @NonNull String orderKey)
    implements Edge<T> {
  @Override
  public T getNode() {
    return node;
  }

  @Override
  public ConnectionCursor getCursor() {
    return cursor;
  }
}
