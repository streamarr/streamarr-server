package com.streamarr.server.graphql.dto;

import com.streamarr.server.services.pagination.MediaOrder;
import graphql.relay.ConnectionCursor;
import graphql.relay.Edge;

public record MediaEdge<T>(T node, ConnectionCursor cursor, MediaOrder order) implements Edge<T> {
  @Override
  public T getNode() {
    return node;
  }

  @Override
  public ConnectionCursor getCursor() {
    return cursor;
  }
}
