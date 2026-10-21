package com.streamarr.server.graphql.cursor;

import com.streamarr.server.graphql.dto.OrderedEdge;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import graphql.relay.Connection;
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
