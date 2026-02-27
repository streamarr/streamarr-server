package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("IntegrationTest")
@DisplayName("Movie Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MovieServiceIT extends AbstractIntegrationTest {

  @Autowired private MovieRepository movieRepository;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private GenreRepository genreRepository;

  @Autowired private CompanyRepository companyRepository;

  @Autowired private PersonRepository personRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

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

  // --- 3a. Nullable Sort Field Pagination (RELEASE_DATE, RUNTIME) ---

  @Test
  @DisplayName("Should place nulls last when sorting by RELEASE_DATE ASC")
  void shouldPlaceNullsLastWhenSortingByReleaseDateAsc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Mid")
            .titleSort("mid")
            .releaseDate(LocalDate.of(2010, 6, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Early")
            .titleSort("early")
            .releaseDate(LocalDate.of(2000, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder().title("None").titleSort("none").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Late")
            .titleSort("late")
            .releaseDate(LocalDate.of(2024, 12, 25))
            .library(library)
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Early", "Mid", "Late", "None");
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by RELEASE_DATE ASC")
  void shouldPaginateForwardUsingCursorWhenSortedByReleaseDateAsc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("First")
            .titleSort("first")
            .releaseDate(LocalDate.of(2000, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Second")
            .titleSort("second")
            .releaseDate(LocalDate.of(2010, 6, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Third")
            .titleSort("third")
            .releaseDate(LocalDate.of(2020, 12, 25))
            .library(library)
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(page1.getEdges().get(0).getNode().getTitle()).isEqualTo("First");
    assertThat(page1.getPageInfo().isHasNextPage()).isTrue();

    var cursor = page1.getPageInfo().getEndCursor().getValue();
    var page2 = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);
    assertThat(page2.getEdges().get(0).getNode().getTitle()).isEqualTo("Second");
    assertThat(page2.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by RELEASE_DATE DESC")
  void shouldPaginateForwardUsingCursorWhenSortedByReleaseDateDesc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Old")
            .titleSort("old")
            .releaseDate(LocalDate.of(2000, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("New")
            .titleSort("new")
            .releaseDate(LocalDate.of(2024, 6, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Mid")
            .titleSort("mid")
            .releaseDate(LocalDate.of(2010, 3, 15))
            .library(library)
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.DESC)
            .build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(page1.getEdges().get(0).getNode().getTitle()).isEqualTo("New");

    var cursor = page1.getPageInfo().getEndCursor().getValue();
    var page2 = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);
    assertThat(page2.getEdges().get(0).getNode().getTitle()).isEqualTo("Mid");
  }

  @Test
  @DisplayName(
      "Should paginate through null RELEASE_DATE values using cursor when cursor is on null row")
  void shouldPaginateThroughNullReleaseDateValuesUsingCursorWhenCursorIsOnNullRow() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Dated")
            .titleSort("dated")
            .releaseDate(LocalDate.of(2000, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Undated A").titleSort("undated a").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Undated B").titleSort("undated b").library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    // Page 1: first=2 returns Dated + one Undated (nulls last, secondary sort by ID)
    var page1 = movieService.getMoviesWithFilter(2, null, 0, null, filter);
    assertThat(page1.getEdges()).hasSize(2);
    assertThat(page1.getEdges().get(0).getNode().getTitle()).isEqualTo("Dated");
    assertThat(page1.getPageInfo().isHasNextPage()).isTrue();

    var page1SecondTitle = page1.getEdges().get(1).getNode().getTitle();

    // Page 2: cursor from null-valued row exercises IS NULL branch of buildSeekCondition
    var cursor = page1.getPageInfo().getEndCursor().getValue();
    var page2 = movieService.getMoviesWithFilter(2, cursor, 0, null, filter);
    assertThat(page2.getEdges()).hasSize(1);
    assertThat(page2.getPageInfo().isHasNextPage()).isFalse();

    var page2Title = page2.getEdges().get(0).getNode().getTitle();

    // Page 2 must contain whichever undated movie was not on page 1
    assertThat(page2Title).isNotEqualTo(page1SecondTitle);
    assertThat(page2Title).startsWith("Undated");

    // All 3 movies seen — no duplicates, no skips
    assertThat(List.of("Dated", page1SecondTitle, page2Title)).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("Should place nulls last when sorting by RUNTIME ASC")
  void shouldPlaceNullsLastWhenSortingByRuntimeAsc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Short").titleSort("short").runtime(90).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("None").titleSort("none").library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Medium").titleSort("medium").runtime(120).library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RUNTIME)
            .sortDirection(SortOrder.ASC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Short", "Medium", "Long", "None");
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by RUNTIME ASC")
  void shouldPaginateForwardUsingCursorWhenSortedByRuntimeAsc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder().title("Short").titleSort("short").runtime(90).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Medium").titleSort("medium").runtime(120).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RUNTIME)
            .sortDirection(SortOrder.ASC)
            .build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(page1.getEdges().get(0).getNode().getTitle()).isEqualTo("Short");

    var cursor = page1.getPageInfo().getEndCursor().getValue();
    var page2 = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);
    assertThat(page2.getEdges().get(0).getNode().getTitle()).isEqualTo("Medium");
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by RUNTIME DESC")
  void shouldPaginateForwardUsingCursorWhenSortedByRuntimeDesc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder().title("Short").titleSort("short").runtime(90).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RUNTIME)
            .sortDirection(SortOrder.DESC)
            .build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(page1.getEdges().get(0).getNode().getTitle()).isEqualTo("Long");

    var cursor = page1.getPageInfo().getEndCursor().getValue();
    var page2 = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);
    assertThat(page2.getEdges().get(0).getNode().getTitle()).isEqualTo("Short");
  }

  // --- 3b. Backward Pagination with Nullable Sort Fields ---

  @Test
  @DisplayName("Should maintain canonical order when paginating backward by RELEASE_DATE ASC")
  void shouldMaintainCanonicalOrderWhenPaginatingBackwardByReleaseDateAsc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("A")
            .titleSort("a")
            .releaseDate(LocalDate.of(2000, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("B")
            .titleSort("b")
            .releaseDate(LocalDate.of(2010, 6, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("C")
            .titleSort("c")
            .releaseDate(LocalDate.of(2020, 12, 25))
            .library(library)
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    var forwardAll = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var forwardTitles = forwardAll.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    var endCursor = forwardAll.getPageInfo().getEndCursor().getValue();
    var backwardPage = movieService.getMoviesWithFilter(0, null, 2, endCursor, filter);
    var backwardTitles = backwardPage.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(backwardTitles).containsExactlyElementsOf(forwardTitles.subList(0, 2));
  }

  @Test
  @DisplayName("Should maintain canonical order when paginating backward by RUNTIME DESC")
  void shouldMaintainCanonicalOrderWhenPaginatingBackwardByRuntimeDesc() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Medium").titleSort("medium").runtime(120).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Short").titleSort("short").runtime(90).library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RUNTIME)
            .sortDirection(SortOrder.DESC)
            .build();

    var forwardAll = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var forwardTitles = forwardAll.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    var endCursor = forwardAll.getPageInfo().getEndCursor().getValue();
    var backwardPage = movieService.getMoviesWithFilter(0, null, 2, endCursor, filter);
    var backwardTitles = backwardPage.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(backwardTitles).containsExactlyElementsOf(forwardTitles.subList(0, 2));
  }

  // --- 3c. PageInfo Correctness (Relay Spec §5) ---

  @Test
  @DisplayName(
      "Should report hasNextPage true and hasPreviousPage false when on first forward page")
  void shouldReportHasNextPageTrueAndHasPreviousPageFalseWhenOnFirstForwardPage() {
    var filter = filterForLibrary(savedLibraryA);

    var result = movieService.getMoviesWithFilter(1, null, 0, null, filter);

    assertThat(result.getPageInfo().isHasNextPage()).isTrue();
    assertThat(result.getPageInfo().isHasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should report hasPreviousPage true when paginating forward with cursor")
  void shouldReportHasPreviousPageTrueWhenPaginatingForwardWithCursor() {
    var filter = filterForLibrary(savedLibraryA);

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var page2 = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);
    assertThat(page2.getPageInfo().isHasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should report hasNextPage false when on final forward page")
  void shouldReportHasNextPageFalseWhenOnFinalForwardPage() {
    var filter = filterForLibrary(savedLibraryA);

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var page2 = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);
    assertThat(page2.getPageInfo().isHasNextPage()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report hasPreviousPage false and hasNextPage true when backward page reaches start")
  void shouldReportHasPreviousPageFalseAndHasNextPageTrueWhenBackwardPageReachesStart() {
    var filter = filterForLibrary(savedLibraryA);

    var allMovies = movieService.getMoviesWithFilter(2, null, 0, null, filter);
    var endCursor = allMovies.getPageInfo().getEndCursor().getValue();

    var backwardPage = movieService.getMoviesWithFilter(0, null, 1, endCursor, filter);

    assertThat(backwardPage.getPageInfo().isHasPreviousPage()).isFalse();
    assertThat(backwardPage.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should set startCursor and endCursor to null when no results match filter")
  void shouldSetStartCursorAndEndCursorToNullWhenNoResultsMatchFilter() {
    var emptyLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var filter = filterForLibrary(emptyLibrary);
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    assertThat(result.getEdges()).isEmpty();
    assertThat(result.getPageInfo().getStartCursor()).isNull();
    assertThat(result.getPageInfo().getEndCursor()).isNull();
  }

  @Test
  @DisplayName("Should set startCursor and endCursor to match edge boundaries when results exist")
  void shouldSetStartCursorAndEndCursorToMatchEdgeBoundariesWhenResultsExist() {
    var filter = filterForLibrary(savedLibraryA);

    var result = movieService.getMoviesWithFilter(2, null, 0, null, filter);

    assertThat(result.getEdges()).hasSize(2);
    assertThat(result.getPageInfo().getStartCursor())
        .isEqualTo(result.getEdges().get(0).getCursor());
    assertThat(result.getPageInfo().getEndCursor()).isEqualTo(result.getEdges().get(1).getCursor());
  }

  // --- 3d. Filter Dimension ITs (Real SQL via jOOQ → PostgreSQL) ---

  @Test
  @DisplayName("Should return only matching movies when genre filter applied")
  void shouldReturnOnlyMatchingMoviesWhenGenreFilterApplied() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var genreAction =
        genreRepository.saveAndFlush(
            Genre.builder().name("Action IT").sourceId("action-it-" + library.getId()).build());
    var genreComedy =
        genreRepository.saveAndFlush(
            Genre.builder().name("Comedy IT").sourceId("comedy-it-" + library.getId()).build());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Action Movie")
            .titleSort("action movie")
            .library(library)
            .genres(Set.of(genreAction))
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Comedy Movie")
            .titleSort("comedy movie")
            .library(library)
            .genres(Set.of(genreComedy))
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .genreIds(List.of(genreAction.getId()))
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Action Movie");
  }

  @Test
  @DisplayName("Should return only matching movies when year filter applied")
  void shouldReturnOnlyMatchingMoviesWhenYearFilterApplied() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Year 2020")
            .titleSort("year 2020")
            .releaseDate(LocalDate.of(2020, 6, 15))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Year 2024")
            .titleSort("year 2024")
            .releaseDate(LocalDate.of(2024, 3, 10))
            .library(library)
            .build());

    var filter = MediaFilter.builder().libraryId(library.getId()).years(List.of(2024)).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Year 2024");
  }

  @Test
  @DisplayName("Should return only matching movies when content rating filter applied")
  void shouldReturnOnlyMatchingMoviesWhenContentRatingFilterApplied() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("PG-13 Movie")
            .titleSort("pg-13 movie")
            .contentRating(new ContentRating("MPAA", "PG-13", "US"))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("R Movie")
            .titleSort("r movie")
            .contentRating(new ContentRating("MPAA", "R", "US"))
            .library(library)
            .build());

    var filter =
        MediaFilter.builder().libraryId(library.getId()).contentRatings(List.of("PG-13")).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("PG-13 Movie");
  }

  @Test
  @DisplayName("Should return only matching movies when studio filter applied")
  void shouldReturnOnlyMatchingMoviesWhenStudioFilterApplied() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var studioA =
        companyRepository.saveAndFlush(
            Company.builder()
                .name("Studio A IT")
                .sourceId("studio-a-it-" + library.getId())
                .build());
    var studioB =
        companyRepository.saveAndFlush(
            Company.builder()
                .name("Studio B IT")
                .sourceId("studio-b-it-" + library.getId())
                .build());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Studio A Movie")
            .titleSort("studio a movie")
            .library(library)
            .studios(Set.of(studioA))
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Studio B Movie")
            .titleSort("studio b movie")
            .library(library)
            .studios(Set.of(studioB))
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .studioIds(List.of(studioA.getId()))
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Studio A Movie");
  }

  @Test
  @DisplayName("Should return only matching movies when director filter applied")
  void shouldReturnOnlyMatchingMoviesWhenDirectorFilterApplied() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var directorA =
        personRepository.saveAndFlush(
            Person.builder().name("Director A IT").sourceId("dir-a-it-" + library.getId()).build());
    var directorB =
        personRepository.saveAndFlush(
            Person.builder().name("Director B IT").sourceId("dir-b-it-" + library.getId()).build());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Dir A Movie")
            .titleSort("dir a movie")
            .library(library)
            .directors(List.of(directorA))
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Dir B Movie")
            .titleSort("dir b movie")
            .library(library)
            .directors(List.of(directorB))
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .directorIds(List.of(directorA.getId()))
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Dir A Movie");
  }

  @Test
  @DisplayName("Should return only matching movies when cast member filter applied")
  void shouldReturnOnlyMatchingMoviesWhenCastMemberFilterApplied() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    var actorA =
        personRepository.saveAndFlush(
            Person.builder().name("Actor A IT").sourceId("actor-a-it-" + library.getId()).build());
    var actorB =
        personRepository.saveAndFlush(
            Person.builder().name("Actor B IT").sourceId("actor-b-it-" + library.getId()).build());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Cast A Movie")
            .titleSort("cast a movie")
            .library(library)
            .cast(List.of(actorA))
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Cast B Movie")
            .titleSort("cast b movie")
            .library(library)
            .cast(List.of(actorB))
            .build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .castMemberIds(List.of(actorA.getId()))
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Cast A Movie");
  }

  @Test
  @DisplayName("Should return only unmatched movies when unmatched filter is true")
  void shouldReturnOnlyUnmatchedMoviesWhenUnmatchedFilterIsTrue() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Unmatched Movie")
            .titleSort("unmatched movie")
            .library(library)
            .build());

    var matchedMovie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Matched Movie")
                .titleSort("matched movie")
                .library(library)
                .build());
    matchedMovie
        .getExternalIds()
        .add(
            ExternalIdentifier.builder()
                .externalSourceType(ExternalSourceType.TMDB)
                .externalId("unmatched-it-" + library.getId())
                .build());
    movieRepository.saveAndFlush(matchedMovie);

    // Insert an orphaned external_identifier with NULL entity_id.
    // This simulates real-world data that breaks NOT IN due to SQL three-valued logic.
    jdbcTemplate.update(
        """
        INSERT INTO external_identifier (id, created_on, created_by, last_modified_on, external_source_type, external_id, entity_id)
        VALUES (gen_random_uuid(), now(), '00000000-0000-0000-0000-000000000000', now(), 'TMDB', ?, NULL)
        """,
        "orphan-" + library.getId());

    var filter = MediaFilter.builder().libraryId(library.getId()).unmatched(true).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Unmatched Movie");
  }

  @Test
  @DisplayName("Should bridge into null RELEASE_DATE values when cursor is on non-null row")
  void shouldBridgeIntoNullReleaseDateValuesWhenCursorIsOnNonNullRow() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Dated A")
            .titleSort("dated a")
            .releaseDate(LocalDate.of(2000, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Dated B")
            .titleSort("dated b")
            .releaseDate(LocalDate.of(2010, 6, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Undated").titleSort("undated").library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    // Page 1: first=2, gets Dated A + Dated B. Cursor encodes non-null date.
    var page1 = movieService.getMoviesWithFilter(2, null, 0, null, filter);
    assertThat(page1.getEdges()).hasSize(2);
    assertThat(page1.getPageInfo().isHasNextPage()).isTrue();

    // Page 2: cursor from Dated B (non-null). Must bridge into null rows.
    var cursor = page1.getPageInfo().getEndCursor().getValue();
    var page2 = movieService.getMoviesWithFilter(2, cursor, 0, null, filter);
    assertThat(page2.getEdges()).hasSize(1);
    assertThat(page2.getEdges().get(0).getNode().getTitle()).isEqualTo("Undated");
    assertThat(page2.getPageInfo().isHasNextPage()).isFalse();
  }

  @Test
  @DisplayName("Should bridge into null RUNTIME values when cursor is on non-null row")
  void shouldBridgeIntoNullRuntimeValuesWhenCursorIsOnNonNullRow() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

    movieRepository.saveAndFlush(
        Movie.builder().title("Short").titleSort("short").runtime(90).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());
    movieRepository.saveAndFlush(
        Movie.builder().title("Unknown").titleSort("unknown").library(library).build());

    var filter =
        MediaFilter.builder()
            .libraryId(library.getId())
            .sortBy(OrderMediaBy.RUNTIME)
            .sortDirection(SortOrder.ASC)
            .build();

    var page1 = movieService.getMoviesWithFilter(2, null, 0, null, filter);
    assertThat(page1.getEdges()).hasSize(2);
    assertThat(page1.getPageInfo().isHasNextPage()).isTrue();

    var cursor = page1.getPageInfo().getEndCursor().getValue();
    var page2 = movieService.getMoviesWithFilter(2, cursor, 0, null, filter);
    assertThat(page2.getEdges()).hasSize(1);
    assertThat(page2.getEdges().get(0).getNode().getTitle()).isEqualTo("Unknown");
    assertThat(page2.getPageInfo().isHasNextPage()).isFalse();
  }

  // --- 3e. Cursor Filter Immutability ---

  @Test
  @DisplayName("Should reject cursor when sortBy changes between pages")
  void shouldRejectCursorWhenSortByChangesBetweenPages() {
    var filter1 =
        MediaFilter.builder().libraryId(savedLibraryA.getId()).sortBy(OrderMediaBy.TITLE).build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter1);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var filter2 =
        MediaFilter.builder().libraryId(savedLibraryA.getId()).sortBy(OrderMediaBy.ADDED).build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, filter2))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should reject cursor when startLetter changes between pages")
  void shouldRejectCursorWhenStartLetterChangesBetweenPages() {
    var filter1 =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.A)
            .build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter1);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var filter2 =
        MediaFilter.builder()
            .libraryId(savedLibraryD.getId())
            .startLetter(AlphabetLetter.B)
            .build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, filter2))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should reject cursor when genreIds change between pages")
  void shouldRejectCursorWhenGenreIdsChangeBetweenPages() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var genre =
        genreRepository.saveAndFlush(
            Genre.builder()
                .name("Genre Cursor IT")
                .sourceId("genre-cursor-it-" + library.getId())
                .build());

    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Genre Movie A")
            .titleSort("genre movie a")
            .library(library)
            .genres(Set.of(genre))
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Genre Movie B")
            .titleSort("genre movie b")
            .library(library)
            .genres(Set.of(genre))
            .build());

    var filter1 =
        MediaFilter.builder().libraryId(library.getId()).genreIds(List.of(genre.getId())).build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter1);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var filter2 =
        MediaFilter.builder()
            .libraryId(library.getId())
            .genreIds(List.of(UUID.randomUUID()))
            .build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, filter2))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should reject cursor when years change between pages")
  void shouldRejectCursorWhenYearsChangeBetweenPages() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Year Test A")
            .titleSort("year test a")
            .releaseDate(LocalDate.of(2024, 1, 1))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Year Test B")
            .titleSort("year test b")
            .releaseDate(LocalDate.of(2024, 6, 1))
            .library(library)
            .build());

    var filter1 = MediaFilter.builder().libraryId(library.getId()).years(List.of(2024)).build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter1);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var filter2 = MediaFilter.builder().libraryId(library.getId()).years(List.of(2023)).build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, filter2))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should reject cursor when contentRatings change between pages")
  void shouldRejectCursorWhenContentRatingsChangeBetweenPages() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Rating Test A")
            .titleSort("rating test a")
            .contentRating(new ContentRating("MPAA", "PG-13", "US"))
            .library(library)
            .build());
    movieRepository.saveAndFlush(
        Movie.builder()
            .title("Rating Test B")
            .titleSort("rating test b")
            .contentRating(new ContentRating("MPAA", "PG-13", "US"))
            .library(library)
            .build());

    var filter1 =
        MediaFilter.builder().libraryId(library.getId()).contentRatings(List.of("PG-13")).build();

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter1);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var filter2 =
        MediaFilter.builder().libraryId(library.getId()).contentRatings(List.of("R")).build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, filter2))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should reject cursor when unmatched changes between pages")
  void shouldRejectCursorWhenUnmatchedChangesBetweenPages() {
    // Page 1 with unmatched=null (default, returns all movies), then change to unmatched=true
    var filter1 = filterForLibrary(savedLibraryA);

    var page1 = movieService.getMoviesWithFilter(1, null, 0, null, filter1);
    var cursor = page1.getPageInfo().getEndCursor().getValue();

    var filter2 = MediaFilter.builder().libraryId(savedLibraryA.getId()).unmatched(true).build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, filter2))
        .isInstanceOf(InvalidCursorException.class);
  }
}
