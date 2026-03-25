package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Relay Connection Adapter Tests")
class RelayConnectionAdapterTest {

  private final CursorUtil cursorUtil = new CursorUtil(new ObjectMapper());
  private final RelayConnectionAdapter adapter = new RelayConnectionAdapter(cursorUtil);

  @Nested
  @DisplayName("Non-Empty Pages")
  class NonEmptyPages {

    @Test
    @DisplayName(
        "Should produce Connection with correct edges and PageInfo when given non-empty page")
    void shouldProduceConnectionWithCorrectEdgesAndPageInfoWhenGivenNonEmptyPage() {
      var id1 = UUID.randomUUID();
      var id2 = UUID.randomUUID();

      var page =
          new MediaPage<>(
              List.of(
                  new PageItem<>(Movie.builder().id(id1).title("Alpha").build(), "Alpha"),
                  new PageItem<>(Movie.builder().id(id2).title("Beta").build(), "Beta")),
              true,
              false);

      var options = buildOptions();

      var connection = adapter.toConnection(page, options);

      assertThat(connection.getEdges()).hasSize(2);
      assertThat(connection.getEdges().get(0).getNode().getTitle()).isEqualTo("Alpha");
      assertThat(connection.getEdges().get(1).getNode().getTitle()).isEqualTo("Beta");
      assertThat(connection.getEdges().get(0).getCursor()).isNotNull();
      assertThat(connection.getEdges().get(1).getCursor()).isNotNull();
      assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
      assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
      assertThat(connection.getPageInfo().getStartCursor())
          .isEqualTo(connection.getEdges().getFirst().getCursor());
      assertThat(connection.getPageInfo().getEndCursor())
          .isEqualTo(connection.getEdges().getLast().getCursor());
    }

    @Test
    @DisplayName("Should set startCursor equal to endCursor when page has single item")
    void shouldSetStartCursorEqualToEndCursorWhenPageHasSingleItem() {
      var page =
          new MediaPage<>(
              List.of(
                  new PageItem<>(
                      Movie.builder().id(UUID.randomUUID()).title("Only").build(), "Only")),
              false,
              false);

      var connection = adapter.toConnection(page, buildOptions());

      assertThat(connection.getEdges()).hasSize(1);
      assertThat(connection.getPageInfo().getStartCursor())
          .isEqualTo(connection.getPageInfo().getEndCursor());
      assertThat(connection.getPageInfo().getStartCursor())
          .isEqualTo(connection.getEdges().getFirst().getCursor());
    }

    @Test
    @DisplayName("Should set both hasNext and hasPrevious when page is middle page")
    void shouldSetBothHasNextAndHasPreviousWhenPageIsMiddlePage() {
      var page =
          new MediaPage<>(
              List.of(
                  new PageItem<>(
                      Movie.builder().id(UUID.randomUUID()).title("Mid").build(), "Mid")),
              true,
              true);

      var connection = adapter.toConnection(page, buildOptions());

      assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
      assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should produce distinct cursors for each edge")
    void shouldProduceDistinctCursorsForEachEdge() {
      var page =
          new MediaPage<>(
              List.of(
                  new PageItem<>(Movie.builder().id(UUID.randomUUID()).title("A").build(), "A"),
                  new PageItem<>(Movie.builder().id(UUID.randomUUID()).title("B").build(), "B"),
                  new PageItem<>(Movie.builder().id(UUID.randomUUID()).title("C").build(), "C")),
              false,
              false);

      var connection = adapter.toConnection(page, buildOptions());

      var cursors = connection.getEdges().stream().map(e -> e.getCursor().getValue()).toList();
      assertThat(cursors).doesNotHaveDuplicates();
    }
  }

  @Nested
  @DisplayName("Empty Pages")
  class EmptyPages {

    @Test
    @DisplayName("Should produce empty Connection when given empty page")
    void shouldProduceEmptyConnectionWhenGivenEmptyPage() {
      var page = new MediaPage<Movie>(List.of(), false, false);
      var options = buildOptions();

      var connection = adapter.toConnection(page, options);

      assertThat(connection.getEdges()).isEmpty();
      assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
      assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
      assertThat(connection.getPageInfo().getStartCursor()).isNull();
      assertThat(connection.getPageInfo().getEndCursor()).isNull();
    }

    @Test
    @DisplayName("Should preserve page flags when items list is empty with hasNext only")
    void shouldPreservePageFlagsWhenItemsListIsEmptyWithHasNextOnly() {
      var page = new MediaPage<Movie>(List.of(), true, false);

      var connection = adapter.toConnection(page, buildOptions());

      assertThat(connection.getEdges()).isEmpty();
      assertThat(connection.getPageInfo().isHasNextPage()).isTrue();
      assertThat(connection.getPageInfo().isHasPreviousPage()).isFalse();
      assertThat(connection.getPageInfo().getStartCursor()).isNull();
      assertThat(connection.getPageInfo().getEndCursor()).isNull();
    }

    @Test
    @DisplayName("Should preserve page flags when items list is empty")
    void shouldPreservePageFlagsWhenItemsListIsEmpty() {
      var page = new MediaPage<Movie>(List.of(), false, true);

      var connection = adapter.toConnection(page, buildOptions());

      assertThat(connection.getEdges()).isEmpty();
      assertThat(connection.getPageInfo().isHasPreviousPage()).isTrue();
      assertThat(connection.getPageInfo().isHasNextPage()).isFalse();
      assertThat(connection.getPageInfo().getStartCursor()).isNull();
      assertThat(connection.getPageInfo().getEndCursor()).isNull();
    }
  }

  @Nested
  @DisplayName("Cursor Encoding")
  class CursorEncoding {

    @Test
    @DisplayName("Should encode cursors that round-trip through CursorUtil")
    void shouldEncodeCursorsThatRoundTripThroughCursorUtil() {
      var id = UUID.randomUUID();

      var page =
          new MediaPage<>(
              List.of(new PageItem<>(Movie.builder().id(id).title("Test").build(), "Test")),
              false,
              false);

      var options = buildOptions();

      var connection = adapter.toConnection(page, options);
      var cursorValue = connection.getEdges().getFirst().getCursor().getValue();

      var decoded =
          cursorUtil.decodeMediaCursor(
              PaginationOptions.builder()
                  .cursor(Optional.of(cursorValue))
                  .paginationDirection(PaginationDirection.FORWARD)
                  .limit(10)
                  .build());

      assertThat(decoded.getCursorId()).contains(id);
      assertThat(decoded.getMediaFilter().getPreviousSortFieldValue()).isEqualTo("Test");
      assertThat(decoded.getMediaFilter().getSortBy()).isEqualTo(options.getMediaFilter().getSortBy());
    }
  }

  private MediaPaginationOptions buildOptions() {
    return MediaPaginationOptions.builder()
        .paginationOptions(
            PaginationOptions.builder()
                .cursor(Optional.empty())
                .paginationDirection(PaginationDirection.FORWARD)
                .limit(10)
                .build())
        .mediaFilter(MediaFilter.builder().build())
        .build();
  }
}
