package com.streamarr.server.graphql.cursor;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.graphql.dto.OrderedEdge;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import graphql.relay.Connection;
import graphql.relay.ConnectionCursor;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import graphql.relay.Edge;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RelayConnectionAdapter {

  private final CursorUtil cursorUtil;

  public <T extends BaseAuditableEntity<?>> Connection<T> toConnection(
      MediaPage<T> page, MediaPaginationOptions options) {
    return toConnection(page, options, Function.identity());
  }

  public <S extends BaseAuditableEntity<?>, T> Connection<T> toConnection(
      MediaPage<S> page, MediaPaginationOptions options, Function<S, T> nodeOf) {
    return toConnection(page, pageItem -> toEdge(pageItem, options, nodeOf));
  }

  public <T extends BaseAuditableEntity<?>> OrderedEdge<T> toEdge(
      PageItem<T> item, MediaPaginationOptions options) {
    return toEdge(item, options, Function.identity());
  }

  private <S extends BaseAuditableEntity<?>, T> OrderedEdge<T> toEdge(
      PageItem<S> item, MediaPaginationOptions options, Function<S, T> nodeOf) {
    return new OrderedEdge<>(
        nodeOf.apply(item.item()),
        cursorUtil.encodeMediaCursor(options, item.item().getId(), item.sortValue()),
        MediaOrder.from(options.getMediaFilter(), item.item().getId(), item.sortValue()));
  }

  public <S, T> Connection<T> toConnection(
      MediaPage<S> page,
      Function<PageItem<S>, T> nodeOf,
      Function<PageItem<S>, ConnectionCursor> cursorOf) {
    return toConnection(
        page, pageItem -> new DefaultEdge<>(nodeOf.apply(pageItem), cursorOf.apply(pageItem)));
  }

  private <S, T> Connection<T> toConnection(
      MediaPage<S> page, Function<PageItem<S>, Edge<T>> edgeOf) {
    if (page.items().isEmpty()) {
      return new DefaultConnection<>(
          Collections.emptyList(),
          new DefaultPageInfo(null, null, page.hasPreviousPage(), page.hasNextPage()));
    }

    List<Edge<T>> edges = page.items().stream().map(edgeOf).toList();

    var first = edges.getFirst();
    var last = edges.getLast();
    var pageInfo =
        new DefaultPageInfo(
            first.getCursor(), last.getCursor(), page.hasPreviousPage(), page.hasNextPage());

    return new DefaultConnection<>(edges, pageInfo);
  }
}
