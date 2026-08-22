package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Keyset Connections Tests")
class KeysetConnectionsTest {

  @Test
  @DisplayName("Should limit edges and report a next page when a lookahead row is fetched")
  void shouldLimitEdgesAndReportNextPageWhenLookaheadRowIsFetched() {
    var connection =
        KeysetConnections.page(
            List.of("a", "b", "c"), value -> value, options(PaginationDirection.FORWARD, null, 2));

    assertThat(connection.getEdges()).extracting(edge -> edge.getNode()).containsExactly("a", "b");
    assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
    assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should return an empty connection when a valid cursor follows the final row")
  void shouldReturnEmptyConnectionWhenValidCursorFollowsFinalRow() {
    var connection =
        KeysetConnections.page(
            List.<String>of(),
            value -> value,
            options(PaginationDirection.FORWARD, "final-cursor", 5));

    assertThat(connection.getEdges()).isEmpty();
    assertThat(connection.getPageInfo().getStartCursor()).isNull();
    assertThat(connection.getPageInfo().getEndCursor()).isNull();
    assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
    assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
  }

  @Test
  @DisplayName("Should restore connection order and page flags when paging before a cursor")
  void shouldRestoreConnectionOrderAndPageFlagsWhenPagingBeforeCursor() {
    var connection =
        KeysetConnections.page(
            List.of("c", "b", "a"), value -> value, options(PaginationDirection.REVERSE, "d", 2));

    assertThat(connection.getEdges()).extracting(edge -> edge.getNode()).containsExactly("b", "c");
    assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
    assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should return the oldest connection rows when paging backward without a cursor")
  void shouldReturnOldestConnectionRowsWhenPagingBackwardWithoutCursor() {
    var connection =
        KeysetConnections.page(
            List.of("e", "d", "c"), value -> value, options(PaginationDirection.REVERSE, null, 2));

    assertThat(connection.getEdges()).extracting(edge -> edge.getNode()).containsExactly("d", "e");
    assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
    assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
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
