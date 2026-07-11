package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.buildBackwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardContinuation;
import static com.streamarr.server.fixtures.PaginationFixture.buildForwardOptions;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

  @Nested
  @DisplayName("Forward Pagination")
  class ForwardPagination {

    @Test
    @DisplayName(
        "Should limit first set of results to one when given 'first' argument and no cursor")
    void shouldLimitFirstSetOfResultsToOneWhenGivenFirstParameterAndNoCursor() {

      var filter = filterForLibrary(savedLibraryA);
      var movies = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));

      assertThat(movies.items()).hasSize(1);
    }

    @Test
    @DisplayName(
        "Should paginate forward twice, one item at a time when given 'first' and 'after' arguments")
    void shouldPaginateForwardLimitingResultsWhenGivenFirstAndCursor() {

      var filter = filterForLibrary(savedLibraryA);

      var firstPageMovies = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));

      assertThat(firstPageMovies.items()).hasSize(1);

      var lastItem = firstPageMovies.items().getLast();
      var secondPageMovies =
          movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));

      assertThat(secondPageMovies.items()).hasSize(1);

      var movie1 = firstPageMovies.items().getFirst();
      var movie2 = secondPageMovies.items().getFirst();

      assertThat(movie1.item().getId()).isNotEqualByComparingTo(movie2.item().getId());
    }

    @Test
    @DisplayName("Should paginate forward within library scope")
    void shouldPaginateForwardWithinLibraryScope() {

      var filter = filterForLibrary(savedLibraryA);

      var firstPage = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
      assertThat(firstPage.items()).hasSize(1);

      var lastItem = firstPage.items().getLast();
      var secondPage =
          movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(secondPage.items()).hasSize(1);

      var allTitles =
          List.of(
              firstPage.items().getFirst().item().getTitle(),
              secondPage.items().getFirst().item().getTitle());

      assertThat(allTitles).containsExactlyInAnyOrder("Alpha", "Beta");
    }
  }

  @Nested
  @DisplayName("Backward Pagination")
  class BackwardPagination {

    @Test
    @DisplayName(
        "Should paginate backward once, after getting first two results when given 'last' and 'before' arguments")
    void shouldPaginateBackwardWhenGivenLastAndCursor() {

      var filter = filterForLibrary(savedLibraryA);

      var firstPageMovies = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));

      assertThat(firstPageMovies.items()).hasSize(2);

      var lastItem = firstPageMovies.items().getLast();
      var secondPageMovies =
          movieService.getMoviesWithFilter(buildBackwardContinuation(1, filter, lastItem));

      assertThat(secondPageMovies.items()).hasSize(1);

      var movie1 = firstPageMovies.items().getFirst();
      var movie2 = secondPageMovies.items().getFirst();

      assertThat(movie1.item().getId()).isEqualTo(movie2.item().getId());
    }

    @Test
    @DisplayName("Should maintain canonical order when paginating backward")
    void shouldMaintainCanonicalOrderWhenPaginatingBackward() {

      var filter = filterForLibrary(savedLibraryD);

      var forwardAll = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));
      var allTitles = forwardAll.items().stream().map(pi -> pi.item().getTitle()).toList();

      var lastItem = forwardAll.items().getLast();
      var backwardPage =
          movieService.getMoviesWithFilter(buildBackwardContinuation(3, filter, lastItem));
      var backwardTitles = backwardPage.items().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(backwardTitles)
          .isSortedAccordingTo(String::compareTo)
          .containsExactlyElementsOf(allTitles.subList(allTitles.size() - 4, allTitles.size() - 1));
    }

    @Test
    @DisplayName("Should paginate backward within library scope")
    void shouldPaginateBackwardWithinLibraryScope() {

      var filter = filterForLibrary(savedLibraryA);

      var allMovies = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));
      assertThat(allMovies.items()).hasSize(2);

      var lastItem = allMovies.items().getLast();
      var lastOne =
          movieService.getMoviesWithFilter(buildBackwardContinuation(1, filter, lastItem));
      assertThat(lastOne.items()).hasSize(1);

      var title = lastOne.items().getFirst().item().getTitle();
      assertThat(title).isEqualTo("Alpha");
    }
  }

  @Nested
  @DisplayName("Sort Orders")
  class SortOrders {

    @Test
    @DisplayName("Should return movies in createdOn descending order when given ADDED DESC sort")
    void shouldReturnMoviesInCreatedOnDescOrderWhenGivenAddedDescSort() {

      var filter =
          MediaFilter.builder()
              .sortBy(OrderMediaBy.ADDED)
              .sortDirection(SortOrder.DESC)
              .libraryId(savedLibraryC.getId())
              .build();

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var firstPage = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));

      assertThat(firstPage.items()).hasSize(1);
      assertThat(firstPage.items().getFirst().item().getTitle()).isEqualTo("First");

      var lastItem = firstPage.items().getLast();
      var secondPage =
          movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));

      assertThat(secondPage.items()).hasSize(1);
      assertThat(secondPage.items().getFirst().item().getTitle()).isEqualTo("Second");
    }

    @Test
    @DisplayName(
        "Should paginate all items with no duplicates or skips when title DESC and duplicate titles")
    void shouldPaginateAllItemsWithNoDuplicatesWhenTitleDescAndDuplicateTitles() {
      var duplicateLibrary =
          libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

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

      var firstPage = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
      assertThat(firstPage.items()).hasSize(1);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem1 = firstPage.items().getLast();
      var secondPage =
          movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem1));
      assertThat(secondPage.items()).hasSize(1);
      assertThat(secondPage.hasNextPage()).isTrue();

      var lastItem2 = secondPage.items().getLast();
      var thirdPage =
          movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem2));
      assertThat(thirdPage.items()).hasSize(1);
      assertThat(thirdPage.hasNextPage()).isFalse();

      var allIds =
          List.of(
              firstPage.items().getFirst().item().getId(),
              secondPage.items().getFirst().item().getId(),
              thirdPage.items().getFirst().item().getId());

      assertThat(allIds).doesNotHaveDuplicates();
    }
  }

  @Nested
  @DisplayName("Library Scoping")
  class LibraryScoping {

    @Test
    @DisplayName("Should return only movies from specified library when libraryId filter is set")
    void shouldReturnOnlyMoviesFromSpecifiedLibraryWhenLibraryIdFilterSet() {

      var filterA = filterForLibrary(savedLibraryA);
      var filterB = filterForLibrary(savedLibraryB);

      var libraryAMovies = movieService.getMoviesWithFilter(buildForwardOptions(10, filterA));
      var libraryBMovies = movieService.getMoviesWithFilter(buildForwardOptions(10, filterB));

      assertThat(libraryAMovies.items()).hasSize(2);
      assertThat(libraryBMovies.items()).hasSize(1);
    }
  }

  @Nested
  @DisplayName("Alphabet Letter Filters")
  class AlphabetLetterFilters {

    @Test
    @DisplayName("Should return movies from A onward when start letter is A")
    void shouldReturnOnlyAlphaMoviesWhenStartLetterIsA() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.A)
              .build();

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var firstPage = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));
      assertThat(firstPage.items()).hasSize(2);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem = firstPage.items().getLast();
      var secondPage =
          movieService.getMoviesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(secondPage.items()).hasSize(2);
      assertThat(secondPage.hasNextPage()).isFalse();

      var allTitles =
          Stream.concat(firstPage.items().stream(), secondPage.items().stream())
              .map(pi -> pi.item().getTitle())
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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var firstPage = movieService.getMoviesWithFilter(buildForwardOptions(3, filter));
      assertThat(firstPage.items()).hasSize(3);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem = firstPage.items().getLast();
      var secondPage =
          movieService.getMoviesWithFilter(buildForwardContinuation(3, filter, lastItem));
      assertThat(secondPage.items()).hasSize(2);
      assertThat(secondPage.hasNextPage()).isFalse();

      var allTitles =
          Stream.concat(firstPage.items().stream(), secondPage.items().stream())
              .map(pi -> pi.item().getTitle())
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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactlyInAnyOrder("123 Numbers", "~Tilde Movie");
    }
  }

  @Nested
  @DisplayName("Nullable Sort Fields")
  class NullableSortFields {

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      var titles = result.items().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("Dated Late", "Dated Early", "Undated");
    }

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Early", "Mid", "Late", "None");
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

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("First");
      assertThat(page1.hasNextPage()).isTrue();

      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Second");
      assertThat(page2.hasNextPage()).isTrue();
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

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("New");

      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Mid");
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
      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));
      assertThat(page1.items()).hasSize(2);
      assertThat(page1.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Dated");
      assertThat(page1.hasNextPage()).isTrue();

      var page1SecondTitle = page1.items().get(1).item().getTitle();

      // Page 2: cursor from null-valued row exercises IS NULL branch of buildSeekCondition
      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(page2.items()).hasSize(1);
      assertThat(page2.hasNextPage()).isFalse();

      var page2Title = page2.items().getFirst().item().getTitle();

      // Page 2 must contain whichever undated movie was not on page 1
      assertThat(page2Title).isNotEqualTo(page1SecondTitle).startsWith("Undated");

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
          Movie.builder()
              .title("Medium")
              .titleSort("medium")
              .runtime(120)
              .library(library)
              .build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RUNTIME)
              .sortDirection(SortOrder.ASC)
              .build();

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Short", "Medium", "Long", "None");
    }

    @Test
    @DisplayName("Should paginate forward using cursor when sorted by RUNTIME ASC")
    void shouldPaginateForwardUsingCursorWhenSortedByRuntimeAsc() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

      movieRepository.saveAndFlush(
          Movie.builder().title("Short").titleSort("short").runtime(90).library(library).build());
      movieRepository.saveAndFlush(
          Movie.builder()
              .title("Medium")
              .titleSort("medium")
              .runtime(120)
              .library(library)
              .build());
      movieRepository.saveAndFlush(
          Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RUNTIME)
              .sortDirection(SortOrder.ASC)
              .build();

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Short");

      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Medium");
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

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));
      assertThat(page1.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Long");

      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Short");
    }

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

      var forwardAll = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));
      var forwardTitles = forwardAll.items().stream().map(pi -> pi.item().getTitle()).toList();

      var lastItem = forwardAll.items().getLast();
      var backwardPage =
          movieService.getMoviesWithFilter(buildBackwardContinuation(2, filter, lastItem));

      assertThat(backwardPage.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactlyElementsOf(forwardTitles.subList(0, 2));
    }

    @Test
    @DisplayName("Should maintain canonical order when paginating backward by RUNTIME DESC")
    void shouldMaintainCanonicalOrderWhenPaginatingBackwardByRuntimeDesc() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

      movieRepository.saveAndFlush(
          Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());
      movieRepository.saveAndFlush(
          Movie.builder()
              .title("Medium")
              .titleSort("medium")
              .runtime(120)
              .library(library)
              .build());
      movieRepository.saveAndFlush(
          Movie.builder().title("Short").titleSort("short").runtime(90).library(library).build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RUNTIME)
              .sortDirection(SortOrder.DESC)
              .build();

      var forwardAll = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));
      var forwardTitles = forwardAll.items().stream().map(pi -> pi.item().getTitle()).toList();

      var lastItem = forwardAll.items().getLast();
      var backwardPage =
          movieService.getMoviesWithFilter(buildBackwardContinuation(2, filter, lastItem));

      assertThat(backwardPage.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactlyElementsOf(forwardTitles.subList(0, 2));
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
      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));
      assertThat(page1.items()).hasSize(2);
      assertThat(page1.hasNextPage()).isTrue();

      // Page 2: cursor from Dated B (non-null). Must bridge into null rows.
      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(page2.items()).hasSize(1);
      assertThat(page2.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Undated");
      assertThat(page2.hasNextPage()).isFalse();
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

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));
      assertThat(page1.items()).hasSize(2);
      assertThat(page1.hasNextPage()).isTrue();

      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(page2.items()).hasSize(1);
      assertThat(page2.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Unknown");
      assertThat(page2.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName(
        "Should paginate through null RUNTIME values using cursor when sorted by RUNTIME DESC")
    void shouldPaginateThroughNullRuntimeValuesUsingCursorWhenSortedByRuntimeDesc() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

      movieRepository.saveAndFlush(
          Movie.builder().title("Long").titleSort("long").runtime(180).library(library).build());
      movieRepository.saveAndFlush(
          Movie.builder().title("Null A").titleSort("null a").library(library).build());
      movieRepository.saveAndFlush(
          Movie.builder().title("Null B").titleSort("null b").library(library).build());

      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .sortBy(OrderMediaBy.RUNTIME)
              .sortDirection(SortOrder.DESC)
              .build();

      // Page 1: first=2 returns Long + one null (NULLS LAST in DESC)
      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));
      assertThat(page1.items()).hasSize(2);
      assertThat(page1.items()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Long");
      assertThat(page1.hasNextPage()).isTrue();

      // Page 2: cursor from null row exercises DESC null-seek branch (line 190)
      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(2, filter, lastItem));
      assertThat(page2.items()).hasSize(1);
      assertThat(page2.hasNextPage()).isFalse();
    }
  }

  @Nested
  @DisplayName("PageInfo Correctness")
  class PageInfoCorrectness {

    @Test
    @DisplayName(
        "Should report hasNextPage true and hasPreviousPage false when on first forward page")
    void shouldReportHasNextPageTrueAndHasPreviousPageFalseWhenOnFirstForwardPage() {
      var filter = filterForLibrary(savedLibraryA);

      var result = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));

      assertThat(result.hasNextPage()).isTrue();
      assertThat(result.hasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should report hasPreviousPage true when paginating forward with cursor")
    void shouldReportHasPreviousPageTrueWhenPaginatingForwardWithCursor() {
      var filter = filterForLibrary(savedLibraryA);

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));

      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.hasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should report hasNextPage false when on final forward page")
    void shouldReportHasNextPageFalseWhenOnFinalForwardPage() {
      var filter = filterForLibrary(savedLibraryA);

      var page1 = movieService.getMoviesWithFilter(buildForwardOptions(1, filter));

      var lastItem = page1.items().getLast();
      var page2 = movieService.getMoviesWithFilter(buildForwardContinuation(1, filter, lastItem));
      assertThat(page2.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report hasPreviousPage false and hasNextPage true when backward page reaches start")
    void shouldReportHasPreviousPageFalseAndHasNextPageTrueWhenBackwardPageReachesStart() {
      var filter = filterForLibrary(savedLibraryA);

      var allMovies = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));

      var lastItem = allMovies.items().getLast();
      var backwardPage =
          movieService.getMoviesWithFilter(buildBackwardContinuation(1, filter, lastItem));

      assertThat(backwardPage.hasPreviousPage()).isFalse();
      assertThat(backwardPage.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName(
        "Should return empty items with hasNextPage true when paginating backward from first item")
    void shouldReturnEmptyItemsWithHasNextPageTrueWhenPaginatingBackwardFromFirstItem() {
      var filter = filterForLibrary(savedLibraryA);

      var firstPage = movieService.getMoviesWithFilter(buildForwardOptions(2, filter));
      var firstItem = firstPage.items().getFirst();

      var backwardFromFirst =
          movieService.getMoviesWithFilter(buildBackwardContinuation(1, filter, firstItem));

      assertThat(backwardFromFirst.items()).isEmpty();
      assertThat(backwardFromFirst.hasNextPage()).isTrue();
    }
  }

  @Nested
  @DisplayName("Filter Dimensions")
  class FilterDimensions {

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Action Movie");
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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Year 2024");
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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("PG-13 Movie");
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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Studio A Movie");
    }

    @Test
    @DisplayName("Should return only matching movies when director filter applied")
    void shouldReturnOnlyMatchingMoviesWhenDirectorFilterApplied() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

      var directorA =
          personRepository.saveAndFlush(
              Person.builder()
                  .name("Director A IT")
                  .sourceId("dir-a-it-" + library.getId())
                  .build());
      var directorB =
          personRepository.saveAndFlush(
              Person.builder()
                  .name("Director B IT")
                  .sourceId("dir-b-it-" + library.getId())
                  .build());

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Dir A Movie");
    }

    @Test
    @DisplayName("Should return only matching movies when cast member filter applied")
    void shouldReturnOnlyMatchingMoviesWhenCastMemberFilterApplied() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

      var actorA =
          personRepository.saveAndFlush(
              Person.builder()
                  .name("Actor A IT")
                  .sourceId("actor-a-it-" + library.getId())
                  .build());
      var actorB =
          personRepository.saveAndFlush(
              Person.builder()
                  .name("Actor B IT")
                  .sourceId("actor-b-it-" + library.getId())
                  .build());

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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Cast A Movie");
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

      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Unmatched Movie");
    }
  }

  @Nested
  @DisplayName("Empty Results")
  class EmptyResults {

    @Test
    @DisplayName("Should return empty items when no results match filter")
    void shouldReturnEmptyItemsWhenNoResultsMatchFilter() {
      var emptyLibrary = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());

      var filter = filterForLibrary(emptyLibrary);
      var result = movieService.getMoviesWithFilter(buildForwardOptions(10, filter));

      assertThat(result.items()).isEmpty();
    }
  }
}
