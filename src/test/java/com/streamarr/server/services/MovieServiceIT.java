package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Movie Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MovieServiceIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieService movieService;

  private Library savedLibraryA;
  private Library savedLibraryB;
  private Library savedLibraryC;
  private Library savedLibraryD;
  private Library savedLibraryE;

  @BeforeAll
  public void setup() {

    var libraryA = LibraryFixtureCreator.buildFakeLibrary();
    savedLibraryA = libraryRepository.saveAndFlush(libraryA);

    var libraryB = LibraryFixtureCreator.buildFakeLibrary();
    savedLibraryB = libraryRepository.saveAndFlush(libraryB);

    var libraryC = LibraryFixtureCreator.buildFakeLibrary();
    savedLibraryC = libraryRepository.saveAndFlush(libraryC);

    var libraryD = LibraryFixtureCreator.buildFakeLibrary();
    savedLibraryD = libraryRepository.saveAndFlush(libraryD);

    movieRepository.saveAndFlush(
        Movie.builder().title("Alpha").titleSort("Alpha").library(savedLibraryA).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Beta").titleSort("Beta").library(savedLibraryA).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Gamma").titleSort("Gamma").library(savedLibraryB).build());

    movieRepository.saveAndFlush(
        Movie.builder().title("First").titleSort("First").library(savedLibraryC).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Second").titleSort("Second").library(savedLibraryC).build());

    movieRepository.saveAndFlush(
        Movie.builder().title("Alpha").titleSort("Alpha").library(savedLibraryD).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Avengers").titleSort("Avengers").library(savedLibraryD).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Batman").titleSort("Batman").library(savedLibraryD).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Beta").titleSort("Beta").library(savedLibraryD).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Gamma").titleSort("Gamma").library(savedLibraryD).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("123 Movie").titleSort("123 Movie").library(savedLibraryD).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Zorro").titleSort("Zorro").library(savedLibraryD).build());

    var libraryE = LibraryFixtureCreator.buildFakeLibrary();
    savedLibraryE = libraryRepository.saveAndFlush(libraryE);

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("~Tilde Movie")
            .titleSort("~tilde movie")
            .releaseDate(LocalDate.of(2024, 1, 1))
            .library(savedLibraryE)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("123 Numbers")
            .titleSort("123 numbers")
            .releaseDate(LocalDate.of(2023, 6, 15))
            .library(savedLibraryE)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Alpha Movie")
            .titleSort("alpha movie")
            .releaseDate(LocalDate.of(2022, 3, 10))
            .library(savedLibraryE)
            .build());
  }

  private MediaFilter filterForLibrary(Library library) {
    return MediaFilter.builder().libraryId(library.getId()).build();
  }

  @Test
  @DisplayName("Should limit first set of results to one when given 'first' argument and no cursor")
  void shouldLimitFirstSetOfResultsToOneWhenGivenFirstParameterAndNoCursor() {

    var filter = filterForLibrary(savedLibraryA);
    var movies = movieService.getMoviesWithFilter(1, null, 0, null, filter);

    assertThat(movies.getEdges()).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should paginate forward twice, one item at a time when given 'first' and 'after' arguments")
  void shouldPaginateForwardLimitingResultsWhenGivenFirstAndCursor() {

    var filter = filterForLibrary(savedLibraryA);

    var firstPageMovies = movieService.getMoviesWithFilter(1, null, 0, null, filter);

    var endCursor = firstPageMovies.getPageInfo().getEndCursor();

    assertThat(firstPageMovies.getEdges()).hasSize(1);

    var secondPageMovies =
        movieService.getMoviesWithFilter(1, endCursor.getValue(), 0, null, filter);

    assertThat(secondPageMovies.getEdges()).hasSize(1);

    var movie1 = firstPageMovies.getEdges().get(0);
    var movie2 = secondPageMovies.getEdges().get(0);

    assertThat(movie1.getNode().getId()).isNotEqualByComparingTo(movie2.getNode().getId());
  }

  @Test
  @DisplayName(
      "Should paginate backward once, after getting first two results when given 'last' and 'before' arguments")
  void shouldPaginateBackwardWhenGivenLastAndCursor() {

    var filter = filterForLibrary(savedLibraryA);

    var firstPageMovies = movieService.getMoviesWithFilter(2, null, 0, null, filter);

    var endCursor = firstPageMovies.getPageInfo().getEndCursor();

    assertThat(firstPageMovies.getEdges()).hasSize(2);

    var secondPageMovies =
        movieService.getMoviesWithFilter(0, null, 1, endCursor.getValue(), filter);

    assertThat(secondPageMovies.getEdges()).hasSize(1);

    var movie1 = firstPageMovies.getEdges().get(0);
    var movie2 = secondPageMovies.getEdges().get(0);

    assertThat(movie1.getNode().getId()).isEqualTo(movie2.getNode().getId());
  }

  @Test
  @DisplayName("Should maintain canonical order when paginating backward")
  void shouldMaintainCanonicalOrderWhenPaginatingBackward() {

    var filter = filterForLibrary(savedLibraryD);

    var forwardAll = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var allTitles = forwardAll.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    var endCursor = forwardAll.getPageInfo().getEndCursor().getValue();
    var backwardPage = movieService.getMoviesWithFilter(0, null, 3, endCursor, filter);
    var backwardTitles = backwardPage.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(backwardTitles).isSortedAccordingTo(String::compareTo);
    assertThat(backwardTitles)
        .containsExactlyElementsOf(allTitles.subList(allTitles.size() - 4, allTitles.size() - 1));
  }

  @Test
  @DisplayName("Should return only movies from specified library when libraryId filter is set")
  void shouldReturnOnlyMoviesFromSpecifiedLibraryWhenLibraryIdFilterSet() {

    var filterA = filterForLibrary(savedLibraryA);
    var filterB = filterForLibrary(savedLibraryB);

    var libraryAMovies = movieService.getMoviesWithFilter(10, null, 0, null, filterA);
    var libraryBMovies = movieService.getMoviesWithFilter(10, null, 0, null, filterB);

    assertThat(libraryAMovies.getEdges()).hasSize(2);
    assertThat(libraryBMovies.getEdges()).hasSize(1);
  }

  @Test
  @DisplayName("Should paginate forward within library scope")
  void shouldPaginateForwardWithinLibraryScope() {

    var filter = filterForLibrary(savedLibraryA);

    var firstPage = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(firstPage.getEdges()).hasSize(1);

    var endCursor = firstPage.getPageInfo().getEndCursor();
    var secondPage = movieService.getMoviesWithFilter(1, endCursor.getValue(), 0, null, filter);
    assertThat(secondPage.getEdges()).hasSize(1);

    var allTitles =
        List.of(
            firstPage.getEdges().get(0).getNode().getTitle(),
            secondPage.getEdges().get(0).getNode().getTitle());

    assertThat(allTitles).containsExactlyInAnyOrder("Alpha", "Beta");
  }

  @Test
  @DisplayName("Should paginate backward within library scope")
  void shouldPaginateBackwardWithinLibraryScope() {

    var filter = filterForLibrary(savedLibraryA);

    var allMovies = movieService.getMoviesWithFilter(2, null, 0, null, filter);
    assertThat(allMovies.getEdges()).hasSize(2);

    var endCursor = allMovies.getPageInfo().getEndCursor();
    var lastOne = movieService.getMoviesWithFilter(0, null, 1, endCursor.getValue(), filter);
    assertThat(lastOne.getEdges()).hasSize(1);

    var title = lastOne.getEdges().get(0).getNode().getTitle();
    assertThat(title).isEqualTo("Alpha");
  }

  @Test
  @DisplayName("Should reject cursor when libraryId does not match")
  void shouldRejectCursorWhenLibraryIdMismatch() {

    var filterA = filterForLibrary(savedLibraryA);
    var filterB = filterForLibrary(savedLibraryB);

    var libraryAMovies = movieService.getMoviesWithFilter(1, null, 0, null, filterA);
    var cursorFromLibraryA = libraryAMovies.getPageInfo().getEndCursor().getValue();

    assertThatThrownBy(
            () -> movieService.getMoviesWithFilter(1, cursorFromLibraryA, 0, null, filterB))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should return movies in createdOn descending order when given ADDED DESC sort")
  void shouldReturnMoviesInCreatedOnDescOrderWhenGivenAddedDescSort() {

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.ADDED)
            .sortDirection(SortOrder.DESC)
            .libraryId(savedLibraryC.getId())
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Second", "First");
  }

  @Test
  @DisplayName("Should paginate forward with ADDED sort order")
  void shouldPaginateForwardWithAddedSortOrder() {

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.ADDED)
            .sortDirection(SortOrder.ASC)
            .libraryId(savedLibraryC.getId())
            .build();

    var firstPage = movieService.getMoviesWithFilter(1, null, 0, null, filter);

    assertThat(firstPage.getEdges()).hasSize(1);
    assertThat(firstPage.getEdges().get(0).getNode().getTitle()).isEqualTo("First");

    var cursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);

    assertThat(secondPage.getEdges()).hasSize(1);
    assertThat(secondPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Second");
  }

  @Test
  @DisplayName(
      "Should paginate all items with no duplicates or skips when title DESC and duplicate titles")
  void shouldPaginateAllItemsWithNoDuplicatesWhenTitleDescAndDuplicateTitles() {
    var duplicateLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Same Title")
            .titleSort("Same Title")
            .library(duplicateLibrary)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Same Title")
            .titleSort("Same Title")
            .library(duplicateLibrary)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Same Title")
            .titleSort("Same Title")
            .library(duplicateLibrary)
            .build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.TITLE)
            .sortDirection(SortOrder.DESC)
            .libraryId(duplicateLibrary.getId())
            .build();

    var firstPage = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(firstPage.getEdges()).hasSize(1);
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var firstCursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = movieService.getMoviesWithFilter(1, firstCursor, 0, null, filter);
    assertThat(secondPage.getEdges()).hasSize(1);
    assertThat(secondPage.getPageInfo().isHasNextPage()).isTrue();

    var secondCursor = secondPage.getPageInfo().getEndCursor().getValue();
    var thirdPage = movieService.getMoviesWithFilter(1, secondCursor, 0, null, filter);
    assertThat(thirdPage.getEdges()).hasSize(1);
    assertThat(thirdPage.getPageInfo().isHasNextPage()).isFalse();

    var allIds =
        List.of(
            firstPage.getEdges().get(0).getNode().getId(),
            secondPage.getEdges().get(0).getNode().getId(),
            thirdPage.getEdges().get(0).getNode().getId());

    assertThat(allIds).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("Should return only alpha movies when start letter is A")
  void shouldReturnOnlyAlphaMoviesWhenStartLetterIsA() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.A)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Alpha", "Avengers", "Batman", "Beta", "Gamma", "Zorro");
  }

  @Test
  @DisplayName("Should return movies from B onward when start letter is B")
  void shouldReturnMoviesFromBOnwardWhenStartLetterIsB() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.B)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Batman", "Beta", "Gamma", "Zorro");
  }

  @Test
  @DisplayName("Should return all movies when start letter is hash")
  void shouldReturnAllMoviesWhenStartLetterIsHash() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.HASH)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles)
        .containsExactly("123 Movie", "Alpha", "Avengers", "Batman", "Beta", "Gamma", "Zorro");
  }

  @Test
  @DisplayName("Should continue pagination onward when start letter is B")
  void shouldContinuePaginationOnwardWhenStartLetterIsB() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.B)
            .build();

    var firstPage = movieService.getMoviesWithFilter(2, null, 0, null, filter);
    assertThat(firstPage.getEdges()).hasSize(2);
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var cursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = movieService.getMoviesWithFilter(2, cursor, 0, null, filter);
    assertThat(secondPage.getEdges()).hasSize(2);
    assertThat(secondPage.getPageInfo().isHasNextPage()).isFalse();

    var allTitles =
        Stream.concat(firstPage.getEdges().stream(), secondPage.getEdges().stream())
            .map(e -> e.getNode().getTitle())
            .toList();

    assertThat(allTitles).containsExactly("Batman", "Beta", "Gamma", "Zorro");
  }

  @Test
  @DisplayName("Should return only Z movies when start letter is Z")
  void shouldReturnOnlyZMoviesWhenStartLetterIsZ() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.Z)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Zorro");
  }

  @Test
  @DisplayName("Should return movies from B backward when start letter is B and sort is DESC")
  void shouldReturnMoviesFromBBackwardWhenStartLetterIsBDesc() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.B)
            .sortDirection(SortOrder.DESC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Beta", "Batman", "Avengers", "Alpha", "123 Movie");
  }

  @Test
  @DisplayName("Should return all movies when start letter is Z and sort is DESC")
  void shouldReturnAllMoviesWhenStartLetterIsZDesc() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.Z)
            .sortDirection(SortOrder.DESC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles)
        .containsExactly("Zorro", "Gamma", "Beta", "Batman", "Avengers", "Alpha", "123 Movie");
  }

  @Test
  @DisplayName("Should return only hash movies when start letter is hash and sort is DESC")
  void shouldReturnOnlyHashMoviesWhenStartLetterIsHashDesc() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.HASH)
            .sortDirection(SortOrder.DESC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("123 Movie");
  }

  @Test
  @DisplayName("Should continue pagination backward when start letter is B and sort is DESC")
  void shouldContinuePaginationOnwardWhenStartLetterIsBDesc() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.B)
            .sortDirection(SortOrder.DESC)
            .build();

    var firstPage = movieService.getMoviesWithFilter(3, null, 0, null, filter);
    assertThat(firstPage.getEdges()).hasSize(3);
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var cursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = movieService.getMoviesWithFilter(3, cursor, 0, null, filter);
    assertThat(secondPage.getEdges()).hasSize(2);
    assertThat(secondPage.getPageInfo().isHasNextPage()).isFalse();

    var allTitles =
        Stream.concat(firstPage.getEdges().stream(), secondPage.getEdges().stream())
            .map(e -> e.getNode().getTitle())
            .toList();

    assertThat(allTitles).containsExactly("Beta", "Batman", "Avengers", "Alpha", "123 Movie");
  }

  @Test
  @DisplayName("Should include title starting above z in HASH filter when sortBy is RELEASE_DATE")
  void shouldIncludeTitleStartingAboveZInHashFilterWhenSortByIsReleaseDate() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryE.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .startLetter(AlphabetLetter.HASH)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("123 Numbers", "~Tilde Movie");
  }

  @Test
  @DisplayName("Should include title starting above z in HASH filter when sortBy is TITLE DESC")
  void shouldIncludeTitleStartingAboveZInHashFilterWhenSortByIsTitleDesc() {

    var filter =
        MediaFilter.builder()
            .libraryId(savedLibraryE.getId())
            .sortBy(OrderMediaBy.TITLE)
            .sortDirection(SortOrder.DESC)
            .startLetter(AlphabetLetter.HASH)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("123 Numbers", "~Tilde Movie");
  }

  @Test
  @DisplayName("Should place null release dates last when sorting by RELEASE_DATE DESC")
  void shouldPlaceNullReleaseDatesLastWhenSortingByReleaseDateDesc() {

    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Dated Early")
            .titleSort("dated early")
            .releaseDate(LocalDate.of(2000, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Dated Late")
            .titleSort("dated late")
            .releaseDate(LocalDate.of(2024, 6, 15))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Undated").titleSort("undated").library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.DESC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Dated Late", "Dated Early", "Undated");
  }
}
