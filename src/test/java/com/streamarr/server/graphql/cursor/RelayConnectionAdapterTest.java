package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.dto.OrderedEdge;
import com.streamarr.server.services.pagination.ListIdentity;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.MediaOrder;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.services.pagination.SortValue;
import graphql.relay.Edge;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Relay Connection Adapter Tests")
class RelayConnectionAdapterTest {

  private static final ListIdentity LIST = new ListIdentity("named-items", "scope");

  private final PositionCursorCodec positionCursors = new PositionCursorCodec(new ObjectMapper());
  private final RelayConnectionAdapter adapter = new RelayConnectionAdapter(positionCursors);

  private record SourceItem(UUID id, String name) {}

  @Nested
  @DisplayName("Non-Empty Pages")
  class NonEmptyPages {

    @Test
    @DisplayName("Should adapt each row into a node whose cursor continues from its position")
    void shouldAdaptEachRowIntoANodeWhoseCursorContinuesFromItsPosition() {
      var row = row("Andrew");

      var connection =
          adapter.toConnection(new ListPage<>(List.of(row), false, false), LIST, SourceItem::name);

      assertThat(connection.getEdges()).extracting(Edge::getNode).containsExactly("Andrew");
      var cursor = connection.getEdges().getFirst().getCursor().getValue();
      assertThat(positionCursors.decode(LIST, cursor)).isEqualTo(row.position());
    }

    @Test
    @DisplayName("Should name the first and last edges' cursors when the page has several rows")
    void shouldNameTheFirstAndLastEdgesCursorsWhenThePageHasSeveralRows() {
      var rows = List.of(row("Alpha"), row("Bravo"), row("Charlie"));

      var connection =
          adapter.toConnection(new ListPage<>(rows, true, false), LIST, SourceItem::name);

      assertThat(connection.getEdges())
          .extracting(Edge::getNode)
          .containsExactly("Alpha", "Bravo", "Charlie");
      var pageInfo = connection.getPageInfo();
      assertThat(pageInfo.getStartCursor()).isEqualTo(connection.getEdges().getFirst().getCursor());
      assertThat(pageInfo.getEndCursor()).isEqualTo(connection.getEdges().getLast().getCursor());
      assertThat(pageInfo.isHasPreviousPage()).isFalse();
      assertThat(pageInfo.isHasNextPage()).isTrue();
    }

    @Test
    @DisplayName("Should set startCursor equal to endCursor when page has single item")
    void shouldSetStartCursorEqualToEndCursorWhenPageHasSingleItem() {
      var connection =
          adapter.toConnection(
              new ListPage<>(List.of(row("Alpha")), false, false), LIST, SourceItem::name);

      assertThat(connection.getPageInfo().getStartCursor())
          .isEqualTo(connection.getPageInfo().getEndCursor());
    }

    @Test
    @DisplayName("Should set both hasNext and hasPrevious when page is middle page")
    void shouldSetBothHasNextAndHasPreviousWhenPageIsMiddlePage() {
      var connection =
          adapter.toConnection(
              new ListPage<>(List.of(row("Alpha")), true, true), LIST, SourceItem::name);

      assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
      assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
    }

    @Test
    @DisplayName("Should produce distinct cursors for each edge")
    void shouldProduceDistinctCursorsForEachEdge() {
      var connection =
          adapter.toConnection(
              new ListPage<>(List.of(row("Alpha"), row("Bravo")), false, false),
              LIST,
              SourceItem::name);

      assertThat(connection.getEdges())
          .extracting(edge -> edge.getCursor().getValue())
          .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Should give each ordered edge the order of its row's position")
    void shouldGiveEachOrderedEdgeTheOrderOfItsRowsPosition() {
      var row = row("Alpha");
      var expected = MediaOrder.at(OrderMediaBy.TITLE, SortDirection.ASC, row.position());

      var connection =
          adapter.toOrderedConnection(
              new ListPage<>(List.of(row), false, false),
              LIST,
              position -> MediaOrder.at(OrderMediaBy.TITLE, SortDirection.ASC, position));

      assertThat(connection.getEdges().getFirst())
          .isInstanceOfSatisfying(
              OrderedEdge.class, edge -> assertThat(edge.order()).isEqualTo(expected));
    }
  }

  @Nested
  @DisplayName("Empty Pages")
  class EmptyPages {

    @Test
    @DisplayName("Should produce an empty connection without cursors when given an empty page")
    void shouldProduceAnEmptyConnectionWithoutCursorsWhenGivenAnEmptyPage() {
      var connection = adapter.toConnection(ListPage.<SourceItem>empty(), LIST, SourceItem::name);

      assertThat(connection.getEdges()).isEmpty();
      assertThat(connection.getPageInfo().getStartCursor()).isNull();
      assertThat(connection.getPageInfo().getEndCursor()).isNull();
      assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
      assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
    }

    @Test
    @DisplayName("Should preserve page flags when an empty page lies between other pages")
    void shouldPreservePageFlagsWhenAnEmptyPageLiesBetweenOtherPages() {
      var connection =
          adapter.toConnection(
              new ListPage<SourceItem>(List.of(), true, true), LIST, SourceItem::name);

      assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
      assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
    }
  }

  private static Row<SourceItem> row(String name) {
    var id = UUID.randomUUID();
    var value = new SortValue.OfText(name.toLowerCase(Locale.ROOT));
    return new Row<>(new SourceItem(id, name), new Position(value, id));
  }
}
