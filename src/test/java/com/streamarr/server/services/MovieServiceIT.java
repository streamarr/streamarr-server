package com.streamarr.server.services;

import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static com.streamarr.server.fixtures.PaginationFixture.rowsAfter;
import static com.streamarr.server.fixtures.PaginationFixture.rowsBefore;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.CompanyRepository;
import com.streamarr.server.repositories.GenreRepository;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.services.pagination.MediaFilter;
import com.streamarr.server.services.pagination.OrderMediaBy;
import com.streamarr.server.services.pagination.SortDirection;
import com.streamarr.server.utils.TitleSortUtil;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

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
  @Autowired private TransactionTemplate transactionTemplate;

  @Autowired private MovieService movieService;

  @Autowired private MediaFileRepository mediaFileRepository;

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
            .title("Ωmega Movie")
            .titleSort("Ωmega movie")
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
  @DisplayName("Should expose cast in billing order when movie has multiple cast members")
  void shouldExposeCastInBillingOrderWhenMovieHasMultipleCastMembers() {
    var library = libraryRepository.save(LibraryFixtureCreator.buildFakeLibrary());
    var secondActor =
        personRepository.save(
            Person.builder().name("Second Billed Actor").sourceId("second-billed-actor-1").build());
    var firstActor =
        personRepository.save(
            Person.builder().name("First Billed Actor").sourceId("first-billed-actor-1").build());
    var movie =
        movieRepository.saveAndFlush(
            Movie.builder()
                .title("Billing Order Movie")
                .library(library)
                .cast(List.of(secondActor, firstActor))
                .build());
    jdbcTemplate.update(
        "UPDATE movie_person SET ordinal = ? WHERE movie_id = ? AND person_id = ?",
        1,
        movie.getId(),
        secondActor.getId());
    jdbcTemplate.update(
        "UPDATE movie_person SET ordinal = ? WHERE movie_id = ? AND person_id = ?",
        0,
        movie.getId(),
        firstActor.getId());

    var cast = movieService.findCast(movie.getId());

    assertThat(cast)
        .extracting(Person::getName)
        .containsExactly("First Billed Actor", "Second Billed Actor");
  }

  @Nested
  @DisplayName("Forward Pagination")
  class ForwardPagination {

    @Test
    @DisplayName(
        "Should limit first set of results to one when given 'first' argument and no cursor")
    void shouldLimitFirstSetOfResultsToOneWhenGivenFirstParameterAndNoCursor() {

      var filter = filterForLibrary(savedLibraryA);
      var movies = movieService.getMoviesWithFilter(filter, firstRows(1));

      assertThat(movies.rows()).hasSize(1);
    }

    @Test
    @DisplayName(
        "Should paginate forward twice, one item at a time when given 'first' and 'after' arguments")
    void shouldPaginateForwardLimitingResultsWhenGivenFirstAndCursor() {

      var filter = filterForLibrary(savedLibraryA);

      var firstPageMovies = movieService.getMoviesWithFilter(filter, firstRows(1));

      assertThat(firstPageMovies.rows()).hasSize(1);

      var lastItem = firstPageMovies.rows().getLast();
      var secondPageMovies = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));

      assertThat(secondPageMovies.rows()).hasSize(1);

      var movie1 = firstPageMovies.rows().getFirst();
      var movie2 = secondPageMovies.rows().getFirst();

      assertThat(movie1.item().getId()).isNotEqualByComparingTo(movie2.item().getId());
    }

    @Test
    @DisplayName("Should paginate forward within library scope")
    void shouldPaginateForwardWithinLibraryScope() {

      var filter = filterForLibrary(savedLibraryA);

      var firstPage = movieService.getMoviesWithFilter(filter, firstRows(1));
      assertThat(firstPage.rows()).hasSize(1);

      var lastItem = firstPage.rows().getLast();
      var secondPage = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));
      assertThat(secondPage.rows()).hasSize(1);

      var allTitles =
          List.of(
              firstPage.rows().getFirst().item().getTitle(),
              secondPage.rows().getFirst().item().getTitle());

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

      var firstPageMovies = movieService.getMoviesWithFilter(filter, firstRows(2));

      assertThat(firstPageMovies.rows()).hasSize(2);

      var lastItem = firstPageMovies.rows().getLast();
      var secondPageMovies = movieService.getMoviesWithFilter(filter, rowsBefore(1, lastItem));

      assertThat(secondPageMovies.rows()).hasSize(1);

      var movie1 = firstPageMovies.rows().getFirst();
      var movie2 = secondPageMovies.rows().getFirst();

      assertThat(movie1.item().getId()).isEqualTo(movie2.item().getId());
    }

    @Test
    @DisplayName("Should maintain canonical order when paginating backward")
    void shouldMaintainCanonicalOrderWhenPaginatingBackward() {

      var filter = filterForLibrary(savedLibraryD);

      var forwardAll = movieService.getMoviesWithFilter(filter, firstRows(10));
      var allTitles = forwardAll.rows().stream().map(pi -> pi.item().getTitle()).toList();

      var lastItem = forwardAll.rows().getLast();
      var backwardPage = movieService.getMoviesWithFilter(filter, rowsBefore(3, lastItem));
      var backwardTitles = backwardPage.rows().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(backwardTitles)
          .isSortedAccordingTo(String::compareTo)
          .containsExactlyElementsOf(allTitles.subList(allTitles.size() - 4, allTitles.size() - 1));
    }

    @Test
    @DisplayName("Should paginate backward within library scope")
    void shouldPaginateBackwardWithinLibraryScope() {

      var filter = filterForLibrary(savedLibraryA);

      var allMovies = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(allMovies.rows()).hasSize(2);

      var lastItem = allMovies.rows().getLast();
      var lastOne = movieService.getMoviesWithFilter(filter, rowsBefore(1, lastItem));
      assertThat(lastOne.rows()).hasSize(1);

      var title = lastOne.rows().getFirst().item().getTitle();
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
              .sortDirection(SortDirection.DESC)
              .libraryId(savedLibraryC.getId())
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("Second", "First");
    }

    @Test
    @DisplayName("Should paginate forward with ADDED sort order")
    void shouldPaginateForwardWithAddedSortOrder() {

      var filter =
          MediaFilter.builder()
              .sortBy(OrderMediaBy.ADDED)
              .sortDirection(SortDirection.ASC)
              .libraryId(savedLibraryC.getId())
              .build();

      var firstPage = movieService.getMoviesWithFilter(filter, firstRows(1));

      assertThat(firstPage.rows()).hasSize(1);
      assertThat(firstPage.rows().getFirst().item().getTitle()).isEqualTo("First");

      var lastItem = firstPage.rows().getLast();
      var secondPage = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));

      assertThat(secondPage.rows()).hasSize(1);
      assertThat(secondPage.rows().getFirst().item().getTitle()).isEqualTo("Second");
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
              .sortDirection(SortDirection.DESC)
              .libraryId(duplicateLibrary.getId())
              .build();

      var firstPage = movieService.getMoviesWithFilter(filter, firstRows(1));
      assertThat(firstPage.rows()).hasSize(1);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem1 = firstPage.rows().getLast();
      var secondPage = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem1));
      assertThat(secondPage.rows()).hasSize(1);
      assertThat(secondPage.hasNextPage()).isTrue();

      var lastItem2 = secondPage.rows().getLast();
      var thirdPage = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem2));
      assertThat(thirdPage.rows()).hasSize(1);
      assertThat(thirdPage.hasNextPage()).isFalse();

      var allIds =
          List.of(
              firstPage.rows().getFirst().item().getId(),
              secondPage.rows().getFirst().item().getId(),
              thirdPage.rows().getFirst().item().getId());

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

      var libraryAMovies = movieService.getMoviesWithFilter(filterA, firstRows(10));
      var libraryBMovies = movieService.getMoviesWithFilter(filterB, firstRows(10));

      assertThat(libraryAMovies.rows()).hasSize(2);
      assertThat(libraryBMovies.rows()).hasSize(1);
    }

    @Test
    @DisplayName(
        "Should leave a file unattached when only another library holds a movie with its TMDB id")
    void shouldLeaveFileUnattachedWhenOnlyAnotherLibraryHoldsMovieWithItsTmdbId() {
      var tmdbId = UUID.randomUUID().toString();
      saveMovieWithTmdbId(saveLibrary(), tmdbId);
      var mediaFile = saveUnmatchedFile(saveLibrary());

      var attachedMovie = movieService.addMediaFileToMovieByTmdbId(tmdbId, mediaFile);

      assertThat(attachedMovie).isEmpty();
      assertThat(mediaFileRepository.findById(mediaFile.getId()))
          .get()
          .extracting(MediaFile::getMediaId)
          .isNull();
    }

    @Test
    @DisplayName(
        "Should attach a file to its own library's movie when another library also holds a movie"
            + " with its TMDB id")
    void shouldAttachFileToItsOwnLibrarysMovieWhenAnotherLibraryAlsoHoldsMovieWithItsTmdbId() {
      var tmdbId = UUID.randomUUID().toString();
      var fileLibrary = saveLibrary();
      saveMovieWithTmdbId(saveLibrary(), tmdbId);
      var ownMovie = saveMovieWithTmdbId(fileLibrary, tmdbId);
      var mediaFile = saveUnmatchedFile(fileLibrary);

      var attachedMovie = movieService.addMediaFileToMovieByTmdbId(tmdbId, mediaFile);

      assertThat(attachedMovie).get().extracting(Movie::getId).isEqualTo(ownMovie.getId());
      assertThat(mediaFileRepository.findById(mediaFile.getId()))
          .get()
          .extracting(MediaFile::getMediaId)
          .isEqualTo(ownMovie.getId());
    }

    private Library saveLibrary() {
      return libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    }

    private Movie saveMovieWithTmdbId(Library library, String tmdbId) {
      return movieRepository.saveAndFlush(
          Movie.builder()
              .title("Inception")
              .library(library)
              .externalIds(
                  Set.of(
                      ExternalIdentifier.builder()
                          .externalId(tmdbId)
                          .externalSourceType(ExternalSourceType.TMDB)
                          .build()))
              .build());
    }

    private MediaFile saveUnmatchedFile(Library library) {
      return mediaFileRepository.saveAndFlush(
          MediaFile.builder()
              .libraryId(library.getId())
              .filepathUri("file:///library/" + UUID.randomUUID() + "/Inception (2010).mkv")
              .filename("Inception (2010).mkv")
              .status(MediaFileStatus.UNMATCHED)
              .build());
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("Batman", "Beta", "Gamma", "Zorro");
    }

    @Test
    @DisplayName("Should keep landing page content when an earlier title sort starts lowercase")
    void shouldKeepLandingPageContentWhenEarlierTitleSortStartsLowercase() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
      movieRepository.saveAllAndFlush(
          Stream.of("Beta", "alpha")
              .map(
                  title ->
                      Movie.builder()
                          .title(title)
                          .titleSort(TitleSortUtil.computeTitleSort(title))
                          .library(library)
                          .build())
              .toList());
      var filter =
          MediaFilter.builder().libraryId(library.getId()).startLetter(AlphabetLetter.B).build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Beta");
    }

    @Test
    @DisplayName("Should land on title sort when display titles precede letter")
    void shouldLandOnTitleSortWhenDisplayTitlesPrecedeLetter() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
      movieRepository.saveAllAndFlush(
          List.of(
              Movie.builder()
                  .title("Aardvark Display")
                  .titleSort("beta sort")
                  .library(library)
                  .build(),
              Movie.builder()
                  .title("Apple Display")
                  .titleSort("Charlie Sort")
                  .library(library)
                  .build()));
      var filter =
          MediaFilter.builder().libraryId(library.getId()).startLetter(AlphabetLetter.B).build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
          .extracting(pageItem -> pageItem.item().getTitle())
          .containsExactly("Aardvark Display", "Apple Display");
      assertThat(result.hasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should return all movies when start letter is hash")
    void shouldReturnAllMoviesWhenStartLetterIsHash() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.HASH)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

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

      var firstPage = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(firstPage.rows()).hasSize(2);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem = firstPage.rows().getLast();
      var secondPage = movieService.getMoviesWithFilter(filter, rowsAfter(2, lastItem));
      assertThat(secondPage.rows()).hasSize(2);
      assertThat(secondPage.hasNextPage()).isFalse();

      var allTitles =
          Stream.concat(firstPage.rows().stream(), secondPage.rows().stream())
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("Zorro");
    }

    @Test
    @DisplayName("Should return movies from B backward when start letter is B and sort is DESC")
    void shouldReturnMoviesFromBBackwardWhenStartLetterIsBDesc() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortDirection(SortDirection.DESC)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("Beta", "Batman", "Avengers", "Alpha", "123 Movie");
    }

    @Test
    @DisplayName("Should return all movies when start letter is Z and sort is DESC")
    void shouldReturnAllMoviesWhenStartLetterIsZDesc() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.Z)
              .sortDirection(SortDirection.DESC)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

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
              .sortDirection(SortDirection.DESC)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactly("123 Movie");
    }

    @Test
    @DisplayName("Should continue pagination backward when start letter is B and sort is DESC")
    void shouldContinuePaginationOnwardWhenStartLetterIsBDesc() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortDirection(SortDirection.DESC)
              .build();

      var firstPage = movieService.getMoviesWithFilter(filter, firstRows(3));
      assertThat(firstPage.rows()).hasSize(3);
      assertThat(firstPage.hasNextPage()).isTrue();

      var lastItem = firstPage.rows().getLast();
      var secondPage = movieService.getMoviesWithFilter(filter, rowsAfter(3, lastItem));
      assertThat(secondPage.rows()).hasSize(2);
      assertThat(secondPage.hasNextPage()).isFalse();

      var allTitles =
          Stream.concat(firstPage.rows().stream(), secondPage.rows().stream())
              .map(pi -> pi.item().getTitle())
              .toList();

      assertThat(allTitles).containsExactly("Beta", "Batman", "Avengers", "Alpha", "123 Movie");
    }

    @Test
    @DisplayName("Should report previous page when letter jump has titles above the anchor")
    void shouldReportPreviousPageWhenLetterJumpHasTitlesAboveAnchor() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(2));

      var titles = landing.rows().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Batman", "Beta");
      assertThat(landing.hasPreviousPage()).isTrue();
      assertThat(landing.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName("Should report previous page when start letter is A and digit titles exist")
    void shouldReportPreviousPageWhenStartLetterIsAAndDigitTitlesExist() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.A)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(landing.rows().getFirst().item().getTitle()).isEqualTo("Alpha");
      assertThat(landing.hasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should not report previous page when letter jump lands at top of library")
    void shouldNotReportPreviousPageWhenLetterJumpLandsAtTopOfLibrary() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryA.getId())
              .startLetter(AlphabetLetter.A)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = landing.rows().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Alpha", "Beta");
      assertThat(landing.hasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should not report previous page when start letter is hash")
    void shouldNotReportPreviousPageWhenStartLetterIsHash() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.HASH)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(landing.rows().getFirst().item().getTitle()).isEqualTo("123 Movie");
      assertThat(landing.hasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should report previous page when start letter is HASH and sort is DESC")
    void shouldReportPreviousPageWhenStartLetterIsHashAndSortIsDesc() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.HASH)
              .sortDirection(SortDirection.DESC)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(landing.rows().getFirst().item().getTitle()).isEqualTo("123 Movie");
      assertThat(landing.hasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should report previous page when letter jump has titles above and sort is DESC")
    void shouldReportPreviousPageWhenLetterJumpHasTitlesAboveAndSortIsDesc() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortDirection(SortDirection.DESC)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(2));

      var titles = landing.rows().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Beta", "Batman");
      assertThat(landing.hasPreviousPage()).isTrue();
      assertThat(landing.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName("Should not report previous page when start letter is Z and sort is DESC")
    void shouldNotReportPreviousPageWhenStartLetterIsZAndSortIsDesc() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.Z)
              .sortDirection(SortDirection.DESC)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(landing.rows().getFirst().item().getTitle()).isEqualTo("Zorro");
      assertThat(landing.hasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report previous page with empty items when letter jump lands past the last title")
    void shouldReportPreviousPageWithEmptyItemsWhenLetterJumpLandsPastLastTitle() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryC.getId())
              .startLetter(AlphabetLetter.T)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(landing.rows()).isEmpty();
      assertThat(landing.hasPreviousPage()).isTrue();
      assertThat(landing.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName("Should page backward past the start letter when sort is TITLE")
    void shouldPageBackwardPastStartLetterWhenSortIsTitle() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(landing.rows().getFirst().item().getTitle()).isEqualTo("Batman");

      var backfill =
          movieService.getMoviesWithFilter(filter, rowsBefore(2, landing.rows().getFirst()));

      var titles = backfill.rows().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Alpha", "Avengers");
      assertThat(backfill.hasNextPage()).isTrue();
      assertThat(backfill.hasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should page backward past the start letter when sort is TITLE DESC")
    void shouldPageBackwardPastStartLetterWhenSortIsTitleDesc() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortDirection(SortDirection.DESC)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(landing.rows().getFirst().item().getTitle()).isEqualTo("Beta");

      var backfill =
          movieService.getMoviesWithFilter(filter, rowsBefore(2, landing.rows().getFirst()));

      var titles = backfill.rows().stream().map(pi -> pi.item().getTitle()).toList();
      assertThat(titles).containsExactly("Zorro", "Gamma");
      assertThat(backfill.hasNextPage()).isTrue();
      assertThat(backfill.hasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should keep start letter equality when continuing under non-TITLE sort")
    void shouldKeepStartLetterEqualityWhenContinuingUnderNonTitleSort() {
      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryD.getId())
              .startLetter(AlphabetLetter.B)
              .sortBy(OrderMediaBy.ADDED)
              .build();

      var firstPage = movieService.getMoviesWithFilter(filter, firstRows(1));
      var secondPage =
          movieService.getMoviesWithFilter(filter, rowsAfter(1, firstPage.rows().getFirst()));

      var titles =
          Stream.concat(firstPage.rows().stream(), secondPage.rows().stream())
              .map(pageItem -> pageItem.item().getTitle())
              .toList();
      assertThat(titles).containsExactlyInAnyOrder("Batman", "Beta");
      assertThat(secondPage.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName("Should return only Z movies when start letter is Z with ADDED sort")
    void shouldReturnOnlyZMoviesWhenStartLetterIsZWithAddedSort() {
      var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
      movieRepository.saveAllAndFlush(
          List.of(
              Movie.builder().title("Alpha").titleSort("alpha").library(library).build(),
              Movie.builder().title("Zorro").titleSort("zorro").library(library).build(),
              Movie.builder().title("Ωmega").titleSort("Ωmega").library(library).build()));
      var filter =
          MediaFilter.builder()
              .libraryId(library.getId())
              .startLetter(AlphabetLetter.Z)
              .sortBy(OrderMediaBy.ADDED)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
          .extracting(pageItem -> pageItem.item().getTitle())
          .containsExactly("Zorro");
    }

    @Test
    @DisplayName("Should include title starting above z in HASH filter when sortBy is RELEASE_DATE")
    void shouldIncludeTitleStartingAboveZInHashFilterWhenSortByIsReleaseDate() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryE.getId())
              .sortBy(OrderMediaBy.RELEASE_DATE)
              .sortDirection(SortDirection.ASC)
              .startLetter(AlphabetLetter.HASH)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

      assertThat(titles).containsExactlyInAnyOrder("123 Numbers", "Ωmega Movie");
    }

    @Test
    @DisplayName(
        "Should land below the lettered titles and page backward to titles above z when start"
            + " letter is HASH and sort is TITLE DESC")
    void shouldLandBelowLetteredTitlesWhenStartLetterIsHashAndSortIsTitleDesc() {

      var filter =
          MediaFilter.builder()
              .libraryId(savedLibraryE.getId())
              .sortBy(OrderMediaBy.TITLE)
              .sortDirection(SortDirection.DESC)
              .startLetter(AlphabetLetter.HASH)
              .build();

      var landing = movieService.getMoviesWithFilter(filter, firstRows(10));
      var above =
          movieService.getMoviesWithFilter(filter, rowsBefore(10, landing.rows().getFirst()));

      assertThat(landing.rows())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("123 Numbers");
      assertThat(landing.hasPreviousPage()).isTrue();
      assertThat(above.rows())
          .extracting(pi -> pi.item().getTitle())
          .containsExactly("Ωmega Movie", "Alpha Movie");
      assertThat(above.hasPreviousPage()).isFalse();
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
              .sortDirection(SortDirection.DESC)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      var titles = result.rows().stream().map(pi -> pi.item().getTitle()).toList();

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
              .sortDirection(SortDirection.ASC)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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
              .sortDirection(SortDirection.ASC)
              .build();

      var page1 = movieService.getMoviesWithFilter(filter, firstRows(1));
      assertThat(page1.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("First");
      assertThat(page1.hasNextPage()).isTrue();

      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));
      assertThat(page2.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Second");
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
              .sortDirection(SortDirection.DESC)
              .build();

      var page1 = movieService.getMoviesWithFilter(filter, firstRows(1));
      assertThat(page1.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("New");

      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));
      assertThat(page2.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Mid");
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
              .sortDirection(SortDirection.ASC)
              .build();

      // Page 1: first=2 returns Dated + one Undated (nulls last, secondary sort by ID)
      var page1 = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(page1.rows()).hasSize(2);
      assertThat(page1.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Dated");
      assertThat(page1.hasNextPage()).isTrue();

      var page1SecondTitle = page1.rows().get(1).item().getTitle();

      // Page 2: cursor from null-valued row exercises IS NULL branch of buildSeekCondition
      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(2, lastItem));
      assertThat(page2.rows()).hasSize(1);
      assertThat(page2.hasNextPage()).isFalse();

      var page2Title = page2.rows().getFirst().item().getTitle();

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
              .sortDirection(SortDirection.ASC)
              .build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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
              .sortDirection(SortDirection.ASC)
              .build();

      var page1 = movieService.getMoviesWithFilter(filter, firstRows(1));
      assertThat(page1.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Short");

      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));
      assertThat(page2.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Medium");
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
              .sortDirection(SortDirection.DESC)
              .build();

      var page1 = movieService.getMoviesWithFilter(filter, firstRows(1));
      assertThat(page1.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Long");

      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));
      assertThat(page2.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Short");
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
              .sortDirection(SortDirection.ASC)
              .build();

      var forwardAll = movieService.getMoviesWithFilter(filter, firstRows(10));
      var forwardTitles = forwardAll.rows().stream().map(pi -> pi.item().getTitle()).toList();

      var lastItem = forwardAll.rows().getLast();
      var backwardPage = movieService.getMoviesWithFilter(filter, rowsBefore(2, lastItem));

      assertThat(backwardPage.rows())
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
              .sortDirection(SortDirection.DESC)
              .build();

      var forwardAll = movieService.getMoviesWithFilter(filter, firstRows(10));
      var forwardTitles = forwardAll.rows().stream().map(pi -> pi.item().getTitle()).toList();

      var lastItem = forwardAll.rows().getLast();
      var backwardPage = movieService.getMoviesWithFilter(filter, rowsBefore(2, lastItem));

      assertThat(backwardPage.rows())
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
              .sortDirection(SortDirection.ASC)
              .build();

      // Page 1: first=2, gets Dated A + Dated B. Cursor encodes non-null date.
      var page1 = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(page1.rows()).hasSize(2);
      assertThat(page1.hasNextPage()).isTrue();

      // Page 2: cursor from Dated B (non-null). Must bridge into null rows.
      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(2, lastItem));
      assertThat(page2.rows()).hasSize(1);
      assertThat(page2.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Undated");
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
              .sortDirection(SortDirection.ASC)
              .build();

      var page1 = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(page1.rows()).hasSize(2);
      assertThat(page1.hasNextPage()).isTrue();

      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(2, lastItem));
      assertThat(page2.rows()).hasSize(1);
      assertThat(page2.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Unknown");
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
              .sortDirection(SortDirection.DESC)
              .build();

      // Page 1: first=2 returns Long + one null (NULLS LAST in DESC)
      var page1 = movieService.getMoviesWithFilter(filter, firstRows(2));
      assertThat(page1.rows()).hasSize(2);
      assertThat(page1.rows()).first().extracting(pi -> pi.item().getTitle()).isEqualTo("Long");
      assertThat(page1.hasNextPage()).isTrue();

      // Page 2: cursor from null row exercises DESC null-seek branch (line 190)
      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(2, lastItem));
      assertThat(page2.rows()).hasSize(1);
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(1));

      assertThat(result.hasNextPage()).isTrue();
      assertThat(result.hasPreviousPage()).isFalse();
    }

    @Test
    @DisplayName("Should report hasPreviousPage true when paginating forward with cursor")
    void shouldReportHasPreviousPageTrueWhenPaginatingForwardWithCursor() {
      var filter = filterForLibrary(savedLibraryA);

      var page1 = movieService.getMoviesWithFilter(filter, firstRows(1));

      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));
      assertThat(page2.hasPreviousPage()).isTrue();
    }

    @Test
    @DisplayName("Should report hasNextPage false when on final forward page")
    void shouldReportHasNextPageFalseWhenOnFinalForwardPage() {
      var filter = filterForLibrary(savedLibraryA);

      var page1 = movieService.getMoviesWithFilter(filter, firstRows(1));

      var lastItem = page1.rows().getLast();
      var page2 = movieService.getMoviesWithFilter(filter, rowsAfter(1, lastItem));
      assertThat(page2.hasNextPage()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report hasPreviousPage false and hasNextPage true when backward page reaches start")
    void shouldReportHasPreviousPageFalseAndHasNextPageTrueWhenBackwardPageReachesStart() {
      var filter = filterForLibrary(savedLibraryA);

      var allMovies = movieService.getMoviesWithFilter(filter, firstRows(2));

      var lastItem = allMovies.rows().getLast();
      var backwardPage = movieService.getMoviesWithFilter(filter, rowsBefore(1, lastItem));

      assertThat(backwardPage.hasPreviousPage()).isFalse();
      assertThat(backwardPage.hasNextPage()).isTrue();
    }

    @Test
    @DisplayName(
        "Should return empty items with hasNextPage true when paginating backward from first item")
    void shouldReturnEmptyItemsWithHasNextPageTrueWhenPaginatingBackwardFromFirstItem() {
      var filter = filterForLibrary(savedLibraryA);

      var firstPage = movieService.getMoviesWithFilter(filter, firstRows(2));
      var firstItem = firstPage.rows().getFirst();

      var backwardFromFirst = movieService.getMoviesWithFilter(filter, rowsBefore(1, firstItem));

      assertThat(backwardFromFirst.rows()).isEmpty();
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows()).extracting(pi -> pi.item().getTitle()).containsExactly("Year 2024");
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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
      transactionTemplate.executeWithoutResult(
          _ ->
              jdbcTemplate.update(
                  """
                  INSERT INTO external_identifier (id, external_source_type, external_id, entity_id)
                  VALUES (gen_random_uuid(), 'TMDB', ?, NULL)
                  """,
                  "orphan-" + library.getId()));

      var filter = MediaFilter.builder().libraryId(library.getId()).unmatched(true).build();

      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows())
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
      var result = movieService.getMoviesWithFilter(filter, firstRows(10));

      assertThat(result.rows()).isEmpty();
    }
  }
}
