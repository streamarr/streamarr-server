package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.pagination.MediaOrder;
import graphql.relay.ConnectionCursor;
import graphql.relay.Edge;
import lombok.NonNull;

public record MediaEdge<T>(
    @NonNull T node, @NonNull ConnectionCursor cursor, @NonNull MediaOrder order)
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
