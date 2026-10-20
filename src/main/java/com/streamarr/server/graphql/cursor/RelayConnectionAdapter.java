package com.streamarr.server.graphql.cursor;

import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.graphql.dto.OrderedEdge;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
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
  private final PositionCursorCodec positionCursors;

  /** Builds edges whose cursors continue {@code list} from each row's position. */
  public <S, T> Connection<T> toConnection(
      ListPage<S> page, ListIdentity list, Function<S, T> nodeOf) {
    return toConnection(
        page.rows(),
        page.hasPreviousPage(),
        page.hasNextPage(),
        row ->
            new DefaultEdge<>(
                nodeOf.apply(row.item()), positionCursors.encode(list, row.position())));
  }

  /** Builds ordered edges whose cursors continue {@code list} from each row's position. */
  public <T> Connection<T> toOrderedConnection(
      ListPage<T> page, ListIdentity list, Function<Position, MediaOrder> orderOf) {
    return toConnection(
        page.rows(), page.hasPreviousPage(), page.hasNextPage(), row -> toEdge(row, list, orderOf));
  }

  public <T> OrderedEdge<T> toEdge(
      Row<T> row, ListIdentity list, Function<Position, MediaOrder> orderOf) {
    return new OrderedEdge<>(
        row.item(), positionCursors.encode(list, row.position()), orderOf.apply(row.position()));
  }

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
    return toConnection(page.items(), page.hasPreviousPage(), page.hasNextPage(), edgeOf);
  }

  private <S, T> Connection<T> toConnection(
      List<S> items,
      boolean hasPreviousPage,
      boolean hasNextPage,
      Function<S, ? extends Edge<T>> edgeOf) {
    if (items.isEmpty()) {
      return new DefaultConnection<>(
          Collections.emptyList(), new DefaultPageInfo(null, null, hasPreviousPage, hasNextPage));
    }

    List<Edge<T>> edges = items.stream().<Edge<T>>map(edgeOf).toList();

    var first = edges.getFirst();
    var last = edges.getLast();
    var pageInfo =
        new DefaultPageInfo(first.getCursor(), last.getCursor(), hasPreviousPage, hasNextPage);

    return new DefaultConnection<>(edges, pageInfo);
  }
}
