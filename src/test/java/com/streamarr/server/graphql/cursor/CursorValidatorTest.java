package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import java.util.List;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Cursor Validator Tests")
class CursorValidatorTest {

  private final CursorValidator cursorValidator = new CursorValidator();

  @Test
  @DisplayName("Should not throw when cursor filter matches current filter")
  void shouldNotThrowWhenCursorFilterMatchesCurrentFilter() {
    var libraryId = UUID.randomUUID();
    var filter = MediaFilter.builder().libraryId(libraryId).sortBy(OrderMediaBy.TITLE).build();
    var decoded = MediaPaginationOptions.builder().mediaFilter(filter).build();

    assertThatNoException()
        .isThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, filter));
  }

  @Test
  @DisplayName("Should throw when libraryId changes between queries")
  void shouldThrowWhenLibraryIdChangesBetweenQueries() {
    var cursorFilter = MediaFilter.builder().libraryId(UUID.randomUUID()).build();
    var currentFilter = MediaFilter.builder().libraryId(UUID.randomUUID()).build();
    var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

    assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessageContaining("libraryId");
  }

  @Test
  @DisplayName("Should throw when sortBy changes between queries")
  void shouldThrowWhenSortByChangesBetweenQueries() {
    var cursorFilter = MediaFilter.builder().sortBy(OrderMediaBy.TITLE).build();
    var currentFilter = MediaFilter.builder().sortBy(OrderMediaBy.ADDED).build();
    var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

    assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessageContaining("sortBy");
  }

  @Test
  @DisplayName("Should throw when sortDirection changes between queries")
  void shouldThrowWhenSortDirectionChangesBetweenQueries() {
    var cursorFilter = MediaFilter.builder().sortDirection(SortOrder.ASC).build();
    var currentFilter = MediaFilter.builder().sortDirection(SortOrder.DESC).build();
    var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

    assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessageContaining("sortDirection");
  }

  @Test
  @DisplayName("Should throw when genreIds change between queries")
  void shouldThrowWhenGenreIdsChangeBetweenQueries() {
    var cursorFilter = MediaFilter.builder().genreIds(List.of(UUID.randomUUID())).build();
    var currentFilter = MediaFilter.builder().genreIds(List.of(UUID.randomUUID())).build();
    var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

    assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
        .isInstanceOf(InvalidCursorException.class)
        .hasMessageContaining("genreIds");
  }

  @Test
  @DisplayName("Should ignore previousSortFieldValue when comparing filters")
  void shouldIgnorePreviousSortFieldValueWhenComparingFilters() {
    var cursorFilter = MediaFilter.builder().previousSortFieldValue("Alpha").build();
    var currentFilter = MediaFilter.builder().previousSortFieldValue("Beta").build();
    var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

    assertThatNoException()
        .isThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter));
  }
}
