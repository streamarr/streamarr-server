package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import graphql.relay.Edge;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("List Connections Tests")
class ListConnectionsTest {

  private static final List<String> ITEMS = List.of("a", "b", "c", "d", "e");

  @Test
  @DisplayName("Should page forward and report a next page when starting without a cursor")
  void shouldPageForwardAndReportNextPageWhenStartingWithoutCursor() {
    var page = ListConnections.page(ITEMS, s -> s, options(PaginationDirection.FORWARD, null, 2));

    assertThat(page.getEdges()).extracting(Edge::getNode).containsExactly("a", "b");
    assertThat(page.getPageInfo().isHasNextPage()).isTrue();
    assertThat(page.getPageInfo().isHasPreviousPage()).isFalse();
    assertThat(page.getPageInfo().getStartCursor().getValue())
        .isEqualTo(ListConnections.encode("a"));
    assertThat(page.getPageInfo().getEndCursor().getValue()).isEqualTo(ListConnections.encode("b"));
  }

  @Test
  @DisplayName("Should seek past the cursor when paging forward")
  void shouldSeekPastCursorWhenPagingForward() {
    var page =
        ListConnections.page(
            ITEMS, s -> s, options(PaginationDirection.FORWARD, ListConnections.encode("b"), 2));

    assertThat(page.getEdges()).extracting(Edge::getNode).containsExactly("c", "d");
    assertThat(page.getPageInfo().isHasPreviousPage()).isTrue();
    assertThat(page.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should page backward when a before cursor is given")
  void shouldPageBackwardWhenBeforeCursorIsGiven() {
    var page =
        ListConnections.page(
            ITEMS, s -> s, options(PaginationDirection.REVERSE, ListConnections.encode("d"), 2));

    assertThat(page.getEdges()).extracting(Edge::getNode).containsExactly("b", "c");
    assertThat(page.getPageInfo().isHasPreviousPage()).isTrue();
    assertThat(page.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should take the last elements when paging backward without a cursor")
  void shouldTakeLastElementsWhenPagingBackwardWithoutCursor() {
    var page = ListConnections.page(ITEMS, s -> s, options(PaginationDirection.REVERSE, null, 2));

    assertThat(page.getEdges()).extracting(Edge::getNode).containsExactly("d", "e");
    assertThat(page.getPageInfo().isHasNextPage()).isFalse();
    assertThat(page.getPageInfo().isHasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should reject a cursor when its anchor disappears instead of repeating prior items")
  void shouldRejectCursorWhenAnchorDisappearsInsteadOfRepeatingPriorItems() {
    var first = ListConnections.page(ITEMS, s -> s, options(PaginationDirection.FORWARD, null, 2));
    var after = first.getPageInfo().getEndCursor().getValue();
    var withoutAnchor = List.of("a", "c", "d", "e");
    var paginationOptions = options(PaginationDirection.FORWARD, after, 2);

    assertThatThrownBy(() -> ListConnections.page(withoutAnchor, s -> s, paginationOptions))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessage("Cursor no longer identifies an item.");
  }

  @Test
  @DisplayName("Should return an empty page with null cursors when nothing matches")
  void shouldReturnEmptyPageWithNullCursorsWhenNothingMatches() {
    var page =
        ListConnections.page(
            List.<String>of(), s -> s, options(PaginationDirection.FORWARD, null, 2));

    assertThat(page.getEdges()).isEmpty();
    assertThat(page.getPageInfo().getStartCursor()).isNull();
    assertThat(page.getPageInfo().isHasNextPage()).isFalse();
  }

  @Test
  @DisplayName("Should reject a cursor when its encoding is not valid base64")
  void shouldRejectCursorWhenEncodingIsNotValidBase64() {
    var paginationOptions = options(PaginationDirection.FORWARD, "%%%", 2);

    assertThatThrownBy(() -> ListConnections.page(ITEMS, s -> s, paginationOptions))
        .isInstanceOf(InvalidCursorException.class);
  }

  private static PaginationOptions options(
      PaginationDirection direction, String cursor, int limit) {
    return PaginationOptions.builder()
        .paginationDirection(direction)
        .cursor(Optional.ofNullable(cursor))
        .limit(limit)
        .build();
  }
}
