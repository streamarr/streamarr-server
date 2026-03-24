package com.streamarr.server.graphql.cursor;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.OrderMediaBy;
import java.util.List;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Cursor Validator Tests")
class CursorValidatorTest {

  private final CursorValidator cursorValidator = new CursorValidator();

  @Nested
  @DisplayName("Matching Filters")
  class MatchingFilters {

    @Test
    @DisplayName("Should not throw when cursor filter matches current filter")
    void shouldNotThrowWhenCursorFilterMatchesCurrentFilter() {
      var libraryId = UUID.randomUUID();
      var filter = MediaFilter.builder().libraryId(libraryId).sortBy(OrderMediaBy.TITLE).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(filter).build();

      assertThatNoException()
          .isThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, filter));
    }
  }

  @Nested
  @DisplayName("Mismatched Filters")
  class MismatchedFilters {

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
    @DisplayName("Should throw when startLetter changes between queries")
    void shouldThrowWhenStartLetterChangesBetweenQueries() {
      var cursorFilter = MediaFilter.builder().startLetter(AlphabetLetter.A).build();
      var currentFilter = MediaFilter.builder().startLetter(AlphabetLetter.B).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("startLetter");
    }

    @Test
    @DisplayName("Should throw when years change between queries")
    void shouldThrowWhenYearsChangeBetweenQueries() {
      var cursorFilter = MediaFilter.builder().years(List.of(2020)).build();
      var currentFilter = MediaFilter.builder().years(List.of(2024)).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("years");
    }

    @Test
    @DisplayName("Should throw when contentRatings change between queries")
    void shouldThrowWhenContentRatingsChangeBetweenQueries() {
      var cursorFilter = MediaFilter.builder().contentRatings(List.of("PG")).build();
      var currentFilter = MediaFilter.builder().contentRatings(List.of("R")).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("contentRatings");
    }

    @Test
    @DisplayName("Should throw when studioIds change between queries")
    void shouldThrowWhenStudioIdsChangeBetweenQueries() {
      var cursorFilter = MediaFilter.builder().studioIds(List.of(UUID.randomUUID())).build();
      var currentFilter = MediaFilter.builder().studioIds(List.of(UUID.randomUUID())).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("studioIds");
    }

    @Test
    @DisplayName("Should throw when directorIds change between queries")
    void shouldThrowWhenDirectorIdsChangeBetweenQueries() {
      var cursorFilter = MediaFilter.builder().directorIds(List.of(UUID.randomUUID())).build();
      var currentFilter = MediaFilter.builder().directorIds(List.of(UUID.randomUUID())).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("directorIds");
    }

    @Test
    @DisplayName("Should throw when castMemberIds change between queries")
    void shouldThrowWhenCastMemberIdsChangeBetweenQueries() {
      var cursorFilter = MediaFilter.builder().castMemberIds(List.of(UUID.randomUUID())).build();
      var currentFilter = MediaFilter.builder().castMemberIds(List.of(UUID.randomUUID())).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("castMemberIds");
    }

    @Test
    @DisplayName("Should throw when unmatched changes between queries")
    void shouldThrowWhenUnmatchedChangesBetweenQueries() {
      var cursorFilter = MediaFilter.builder().unmatched(true).build();
      var currentFilter = MediaFilter.builder().unmatched(false).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("unmatched");
    }

    @Test
    @DisplayName("Should throw when cursor has null genreIds but current has non-null")
    void shouldThrowWhenCursorHasNullGenreIdsButCurrentHasNonNull() {
      var cursorFilter = MediaFilter.builder().build();
      var currentFilter = MediaFilter.builder().genreIds(List.of(UUID.randomUUID())).build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("genreIds");
    }

    @Test
    @DisplayName("Should throw when cursor has non-null genreIds but current has null")
    void shouldThrowWhenCursorHasNonNullGenreIdsButCurrentHasNull() {
      var cursorFilter = MediaFilter.builder().genreIds(List.of(UUID.randomUUID())).build();
      var currentFilter = MediaFilter.builder().build();
      var decoded = MediaPaginationOptions.builder().mediaFilter(cursorFilter).build();

      assertThatThrownBy(() -> cursorValidator.validateCursorAgainstFilter(decoded, currentFilter))
          .isInstanceOf(InvalidCursorException.class)
          .hasMessageContaining("genreIds");
    }
  }

  @Nested
  @DisplayName("Ignored Fields")
  class IgnoredFields {

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
}
