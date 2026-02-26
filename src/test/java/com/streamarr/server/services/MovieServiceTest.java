package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.streamarr.server.config.ImageProperties;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.ContentRating;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.metadata.Company;
import com.streamarr.server.domain.metadata.Genre;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeImageRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.InvalidCursorException;
import com.streamarr.server.graphql.cursor.MediaFilter;
import com.streamarr.server.graphql.cursor.OrderMediaBy;
import com.streamarr.server.services.metadata.ImageVariantService;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.SortOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Movie Service Tests")
class MovieServiceTest {

  private FakeMovieRepository movieRepository;
  private FakeImageRepository imageRepository;
  private CapturingEventPublisher eventPublisher;
  private PersonService personService;
  private GenreService genreService;
  private CompanyService companyService;
  private MovieService movieService;

  @BeforeEach
  void setUp() {
    movieRepository = new FakeMovieRepository();
    imageRepository = new FakeImageRepository();
    eventPublisher = new CapturingEventPublisher();
    personService = mock(PersonService.class);
    genreService = mock(GenreService.class);
    companyService = mock(CompanyService.class);
    var cursorUtil = new CursorUtil(new ObjectMapper());
    var relayPaginationService = new RelayPaginationService();
    var fileSystem = Jimfs.newFileSystem(Configuration.unix());
    var imageService =
        new ImageService(
            imageRepository,
            new ImageVariantService(),
            new ImageProperties("/data/images"),
            fileSystem);
    movieService =
        new MovieService(
            movieRepository,
            personService,
            genreService,
            companyService,
            cursorUtil,
            relayPaginationService,
            eventPublisher,
            imageService,
            null,
            null,
            null,
            null,
            null,
            null);
  }

  @Test
  @DisplayName("Should apply default title ascending sort when given null filter")
  void shouldApplyDefaultTitleAscSortWhenGivenNullFilter() {
    movieRepository.save(Movie.builder().title("Zebra").build());
    movieRepository.save(Movie.builder().title("Apple").build());

    var result = movieService.getMoviesWithFilter(10, null, 0, null, null);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Apple", "Zebra");
  }

  @Test
  @DisplayName("Should apply provided sort direction when given explicit filter")
  void shouldApplyProvidedSortDirectionWhenGivenExplicitFilter() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Zebra").build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Zebra", "Apple");
  }

  @Test
  @DisplayName("Should paginate forward using cursor when sorted by title")
  void shouldPaginateForwardUsingCursorWhenSortedByTitle() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());
    movieRepository.save(Movie.builder().title("Cherry").build());

    var firstPage = movieService.getMoviesWithFilter(1, null, 0, null, null);

    assertThat(firstPage.getEdges()).hasSize(1);
    assertThat(firstPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Apple");
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var cursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = movieService.getMoviesWithFilter(1, cursor, 0, null, null);

    assertThat(secondPage.getEdges()).hasSize(1);
    assertThat(secondPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Banana");
    assertThat(secondPage.getPageInfo().isHasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should paginate backward using cursor when sorted by title")
  void shouldPaginateBackwardUsingCursorWhenSortedByTitle() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());
    movieRepository.save(Movie.builder().title("Cherry").build());

    var allMovies = movieService.getMoviesWithFilter(3, null, 0, null, null);
    var endCursor = allMovies.getPageInfo().getEndCursor().getValue();

    var backwardPage = movieService.getMoviesWithFilter(0, null, 1, endCursor, null);

    assertThat(backwardPage.getEdges()).hasSize(1);
    assertThat(backwardPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Banana");
    assertThat(backwardPage.getPageInfo().isHasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should paginate backward from DESC maintaining canonical order")
  void shouldPaginateBackwardFromDescMaintainingCanonicalOrder() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());
    movieRepository.save(Movie.builder().title("Cherry").build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    var allMovies = movieService.getMoviesWithFilter(3, null, 0, null, filter);
    assertThat(allMovies.getEdges()).hasSize(3);

    var endCursor = allMovies.getPageInfo().getEndCursor().getValue();
    var backwardPage = movieService.getMoviesWithFilter(0, null, 1, endCursor, filter);

    assertThat(backwardPage.getEdges()).hasSize(1);
    assertThat(backwardPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Banana");
  }

  @Test
  @DisplayName("Should maintain canonical order when paginating backward")
  void shouldMaintainCanonicalOrderWhenPaginatingBackward() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());
    movieRepository.save(Movie.builder().title("Cherry").build());
    movieRepository.save(Movie.builder().title("Date").build());

    var forwardAll = movieService.getMoviesWithFilter(4, null, 0, null, null);
    var forwardTitles =
        forwardAll.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    var endCursor = forwardAll.getPageInfo().getEndCursor().getValue();
    var backwardPage = movieService.getMoviesWithFilter(0, null, 3, endCursor, null);
    var backwardTitles =
        backwardPage.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(backwardTitles).containsExactlyElementsOf(forwardTitles.subList(0, 3));
  }

  @Test
  @DisplayName("Should return empty connection when no movies exist")
  void shouldReturnEmptyConnectionWhenNoMoviesExist() {
    var result = movieService.getMoviesWithFilter(10, null, 0, null, null);

    assertThat(result.getEdges()).isEmpty();
    assertThat(result.getPageInfo().isHasNextPage()).isFalse();
    assertThat(result.getPageInfo().isHasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should filter by start letter as equality when sort is not TITLE")
  void shouldFilterByStartLetterAsEqualityWhenSortIsNotTitle() {
    var libraryId = UUID.randomUUID();
    var library = Library.builder().id(libraryId).name("Movies").build();
    movieRepository.save(Movie.builder().title("Alpha").library(library).build());
    movieRepository.save(Movie.builder().title("Avengers").library(library).build());
    movieRepository.save(Movie.builder().title("Batman").library(library).build());
    movieRepository.save(Movie.builder().title("Cherry").library(library).build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.ADDED)
            .sortDirection(SortOrder.ASC)
            .libraryId(libraryId)
            .startLetter(com.streamarr.server.domain.AlphabetLetter.A)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("Alpha", "Avengers");
  }

  @Test
  @DisplayName("Should filter by start letter as range when sort is TITLE")
  void shouldFilterByStartLetterAsRangeWhenSortIsTitle() {
    var libraryId = UUID.randomUUID();
    var library = Library.builder().id(libraryId).name("Movies").build();
    movieRepository.save(Movie.builder().title("Alpha").library(library).build());
    movieRepository.save(Movie.builder().title("Avengers").library(library).build());
    movieRepository.save(Movie.builder().title("Batman").library(library).build());
    movieRepository.save(Movie.builder().title("Cherry").library(library).build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.TITLE)
            .sortDirection(SortOrder.ASC)
            .libraryId(libraryId)
            .startLetter(com.streamarr.server.domain.AlphabetLetter.A)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Alpha", "Avengers", "Batman", "Cherry");
  }

  @Test
  @DisplayName("Should filter by single genre when genreIds has one ID")
  void shouldFilterBySingleGenre() {
    var genreAction = Genre.builder().name("Action").sourceId("action").build();
    genreAction.setId(UUID.randomUUID());
    var genreComedy = Genre.builder().name("Comedy").sourceId("comedy").build();
    genreComedy.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Action Movie").genres(Set.of(genreAction)).build());
    movieRepository.save(
        Movie.builder().title("Comedy Movie").genres(Set.of(genreComedy)).build());
    movieRepository.save(
        Movie.builder()
            .title("Action Comedy")
            .genres(Set.of(genreAction, genreComedy))
            .build());

    var filter = MediaFilter.builder().genreIds(List.of(genreAction.getId())).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("Action Movie", "Action Comedy");
  }

  @Test
  @DisplayName("Should filter by multiple genres with OR semantics")
  void shouldFilterByMultipleGenresWithOrSemantics() {
    var genreAction = Genre.builder().name("Action").sourceId("action").build();
    genreAction.setId(UUID.randomUUID());
    var genreComedy = Genre.builder().name("Comedy").sourceId("comedy").build();
    genreComedy.setId(UUID.randomUUID());
    var genreDrama = Genre.builder().name("Drama").sourceId("drama").build();
    genreDrama.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Action Movie").genres(Set.of(genreAction)).build());
    movieRepository.save(
        Movie.builder().title("Comedy Movie").genres(Set.of(genreComedy)).build());
    movieRepository.save(Movie.builder().title("Drama Movie").genres(Set.of(genreDrama)).build());

    var filter =
        MediaFilter.builder()
            .genreIds(List.of(genreAction.getId(), genreComedy.getId()))
            .build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("Action Movie", "Comedy Movie");
  }

  @Test
  @DisplayName("Should return all movies when genreIds is empty")
  void shouldReturnAllWhenGenreIdsEmpty() {
    movieRepository.save(Movie.builder().title("Movie A").build());
    movieRepository.save(Movie.builder().title("Movie B").build());

    var filter = MediaFilter.builder().genreIds(List.of()).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    assertThat(result.getEdges()).hasSize(2);
  }

  @Test
  @DisplayName("Should filter by single year when years has one value")
  void shouldFilterBySingleYear() {
    movieRepository.save(
        Movie.builder().title("Old Movie").releaseDate(LocalDate.of(2000, 6, 15)).build());
    movieRepository.save(
        Movie.builder().title("New Movie").releaseDate(LocalDate.of(2024, 3, 10)).build());
    movieRepository.save(
        Movie.builder().title("Mid Movie").releaseDate(LocalDate.of(2010, 11, 1)).build());

    var filter = MediaFilter.builder().years(List.of(2024)).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("New Movie");
  }

  @Test
  @DisplayName("Should filter by multiple years with OR semantics")
  void shouldFilterByMultipleYearsWithOrSemantics() {
    movieRepository.save(
        Movie.builder().title("Year 2000").releaseDate(LocalDate.of(2000, 1, 1)).build());
    movieRepository.save(
        Movie.builder().title("Year 2010").releaseDate(LocalDate.of(2010, 6, 15)).build());
    movieRepository.save(
        Movie.builder().title("Year 2020").releaseDate(LocalDate.of(2020, 12, 25)).build());

    var filter = MediaFilter.builder().years(List.of(2000, 2020)).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("Year 2000", "Year 2020");
  }

  @Test
  @DisplayName("Should exclude null release date when filtering by year")
  void shouldExcludeNullReleaseDateWhenFilteringByYear() {
    movieRepository.save(
        Movie.builder().title("Dated").releaseDate(LocalDate.of(2024, 1, 1)).build());
    movieRepository.save(Movie.builder().title("Undated").build());

    var filter = MediaFilter.builder().years(List.of(2024)).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Dated");
  }

  @Test
  @DisplayName("Should filter by single content rating")
  void shouldFilterBySingleContentRating() {
    movieRepository.save(
        Movie.builder()
            .title("PG-13 Movie")
            .contentRating(new ContentRating("MPAA", "PG-13", "US"))
            .build());
    movieRepository.save(
        Movie.builder()
            .title("R Movie")
            .contentRating(new ContentRating("MPAA", "R", "US"))
            .build());

    var filter = MediaFilter.builder().contentRatings(List.of("PG-13")).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("PG-13 Movie");
  }

  @Test
  @DisplayName("Should filter by multiple content ratings with OR semantics")
  void shouldFilterByMultipleContentRatingsWithOrSemantics() {
    movieRepository.save(
        Movie.builder()
            .title("PG Movie")
            .contentRating(new ContentRating("MPAA", "PG", "US"))
            .build());
    movieRepository.save(
        Movie.builder()
            .title("R Movie")
            .contentRating(new ContentRating("MPAA", "R", "US"))
            .build());
    movieRepository.save(
        Movie.builder()
            .title("G Movie")
            .contentRating(new ContentRating("MPAA", "G", "US"))
            .build());

    var filter = MediaFilter.builder().contentRatings(List.of("PG", "R")).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("PG Movie", "R Movie");
  }

  @Test
  @DisplayName("Should filter by single studio when studioIds has one ID")
  void shouldFilterBySingleStudio() {
    var studioA = Company.builder().name("Studio A").build();
    studioA.setId(UUID.randomUUID());
    var studioB = Company.builder().name("Studio B").build();
    studioB.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Studio A Movie").studios(Set.of(studioA)).build());
    movieRepository.save(
        Movie.builder().title("Studio B Movie").studios(Set.of(studioB)).build());

    var filter = MediaFilter.builder().studioIds(List.of(studioA.getId())).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Studio A Movie");
  }

  @Test
  @DisplayName("Should filter by multiple studios with OR semantics")
  void shouldFilterByMultipleStudiosWithOrSemantics() {
    var studioA = Company.builder().name("Studio A").build();
    studioA.setId(UUID.randomUUID());
    var studioB = Company.builder().name("Studio B").build();
    studioB.setId(UUID.randomUUID());
    var studioC = Company.builder().name("Studio C").build();
    studioC.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Studio A Movie").studios(Set.of(studioA)).build());
    movieRepository.save(
        Movie.builder().title("Studio B Movie").studios(Set.of(studioB)).build());
    movieRepository.save(
        Movie.builder().title("Studio C Movie").studios(Set.of(studioC)).build());

    var filter =
        MediaFilter.builder()
            .studioIds(List.of(studioA.getId(), studioB.getId()))
            .build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("Studio A Movie", "Studio B Movie");
  }

  @Test
  @DisplayName("Should filter by single director when directorIds has one ID")
  void shouldFilterBySingleDirector() {
    var directorA = Person.builder().name("Director A").build();
    directorA.setId(UUID.randomUUID());
    var directorB = Person.builder().name("Director B").build();
    directorB.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Director A Movie").directors(List.of(directorA)).build());
    movieRepository.save(
        Movie.builder().title("Director B Movie").directors(List.of(directorB)).build());

    var filter = MediaFilter.builder().directorIds(List.of(directorA.getId())).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Director A Movie");
  }

  @Test
  @DisplayName("Should filter by multiple directors with OR semantics")
  void shouldFilterByMultipleDirectorsWithOrSemantics() {
    var dirA = Person.builder().name("Dir A").build();
    dirA.setId(UUID.randomUUID());
    var dirB = Person.builder().name("Dir B").build();
    dirB.setId(UUID.randomUUID());
    var dirC = Person.builder().name("Dir C").build();
    dirC.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Movie A").directors(List.of(dirA)).build());
    movieRepository.save(
        Movie.builder().title("Movie B").directors(List.of(dirB)).build());
    movieRepository.save(
        Movie.builder().title("Movie C").directors(List.of(dirC)).build());

    var filter =
        MediaFilter.builder().directorIds(List.of(dirA.getId(), dirB.getId())).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("Movie A", "Movie B");
  }

  @Test
  @DisplayName("Should filter by single cast member when castMemberIds has one ID")
  void shouldFilterBySingleCastMember() {
    var actorA = Person.builder().name("Actor A").build();
    actorA.setId(UUID.randomUUID());
    var actorB = Person.builder().name("Actor B").build();
    actorB.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Actor A Movie").cast(List.of(actorA)).build());
    movieRepository.save(
        Movie.builder().title("Actor B Movie").cast(List.of(actorB)).build());

    var filter = MediaFilter.builder().castMemberIds(List.of(actorA.getId())).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Actor A Movie");
  }

  @Test
  @DisplayName("Should filter by multiple cast members with OR semantics")
  void shouldFilterByMultipleCastMembersWithOrSemantics() {
    var actorA = Person.builder().name("Actor A").build();
    actorA.setId(UUID.randomUUID());
    var actorB = Person.builder().name("Actor B").build();
    actorB.setId(UUID.randomUUID());
    var actorC = Person.builder().name("Actor C").build();
    actorC.setId(UUID.randomUUID());

    movieRepository.save(
        Movie.builder().title("Movie A").cast(List.of(actorA)).build());
    movieRepository.save(
        Movie.builder().title("Movie B").cast(List.of(actorB)).build());
    movieRepository.save(
        Movie.builder().title("Movie C").cast(List.of(actorC)).build());

    var filter =
        MediaFilter.builder()
            .castMemberIds(List.of(actorA.getId(), actorB.getId()))
            .build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactlyInAnyOrder("Movie A", "Movie B");
  }

  @Test
  @DisplayName("Should return only unmatched movies when unmatched is true")
  void shouldReturnOnlyUnmatchedWhenUnmatchedTrue() {
    movieRepository.save(Movie.builder().title("Unmatched Movie").build());
    movieRepository.save(
        Movie.builder()
            .title("Matched Movie")
            .externalIds(
                Set.of(
                    com.streamarr.server.domain.ExternalIdentifier.builder()
                        .externalSourceType(
                            com.streamarr.server.domain.ExternalSourceType.TMDB)
                        .externalId("12345")
                        .build()))
            .build());

    var filter = MediaFilter.builder().unmatched(true).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Unmatched Movie");
  }

  @Test
  @DisplayName("Should return all movies when unmatched is null")
  void shouldReturnAllWhenUnmatchedNull() {
    movieRepository.save(Movie.builder().title("Movie A").build());
    movieRepository.save(Movie.builder().title("Movie B").build());

    var filter = MediaFilter.builder().unmatched(null).build();
    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);

    assertThat(result.getEdges()).hasSize(2);
  }

  @Test
  @DisplayName("Should reject cursor when sort direction changes between queries")
  void shouldRejectCursorWhenSortDirectionChanges() {
    movieRepository.save(Movie.builder().title("Apple").build());
    movieRepository.save(Movie.builder().title("Banana").build());

    var ascResult = movieService.getMoviesWithFilter(1, null, 0, null, null);
    var cursor = ascResult.getPageInfo().getEndCursor().getValue();

    var descFilter =
        MediaFilter.builder().sortBy(OrderMediaBy.TITLE).sortDirection(SortOrder.DESC).build();

    assertThatThrownBy(() -> movieService.getMoviesWithFilter(1, cursor, 0, null, descFilter))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  @DisplayName("Should sort by release date ascending when sort is RELEASE_DATE ASC")
  void shouldSortByReleaseDateAsc() {
    movieRepository.save(
        Movie.builder().title("Old Movie").releaseDate(LocalDate.of(2000, 1, 1)).build());
    movieRepository.save(
        Movie.builder().title("New Movie").releaseDate(LocalDate.of(2024, 6, 15)).build());
    movieRepository.save(
        Movie.builder().title("Mid Movie").releaseDate(LocalDate.of(2010, 3, 20)).build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Old Movie", "Mid Movie", "New Movie");
  }

  @Test
  @DisplayName("Should sort by release date descending when sort is RELEASE_DATE DESC")
  void shouldSortByReleaseDateDesc() {
    movieRepository.save(
        Movie.builder().title("Old Movie").releaseDate(LocalDate.of(2000, 1, 1)).build());
    movieRepository.save(
        Movie.builder().title("New Movie").releaseDate(LocalDate.of(2024, 6, 15)).build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.DESC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("New Movie", "Old Movie");
  }

  @Test
  @DisplayName("Should sort by runtime ascending when sort is RUNTIME ASC")
  void shouldSortByRuntimeAsc() {
    movieRepository.save(Movie.builder().title("Short").runtime(90).build());
    movieRepository.save(Movie.builder().title("Long").runtime(180).build());
    movieRepository.save(Movie.builder().title("Medium").runtime(120).build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.RUNTIME).sortDirection(SortOrder.ASC).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Short", "Medium", "Long");
  }

  @Test
  @DisplayName("Should sort by runtime descending when sort is RUNTIME DESC")
  void shouldSortByRuntimeDesc() {
    movieRepository.save(Movie.builder().title("Short").runtime(90).build());
    movieRepository.save(Movie.builder().title("Long").runtime(180).build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.RUNTIME).sortDirection(SortOrder.DESC).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Long", "Short");
  }

  @Test
  @DisplayName("Should place null release date last when sorting by RELEASE_DATE ASC")
  void shouldPlaceNullReleaseDateLast() {
    movieRepository.save(
        Movie.builder().title("Dated Movie").releaseDate(LocalDate.of(2020, 1, 1)).build());
    movieRepository.save(Movie.builder().title("Undated Movie").build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("Dated Movie", "Undated Movie");
  }

  @Test
  @DisplayName("Should place null runtime last when sorting by RUNTIME ASC")
  void shouldPlaceNullRuntimeLast() {
    movieRepository.save(Movie.builder().title("With Runtime").runtime(120).build());
    movieRepository.save(Movie.builder().title("No Runtime").build());

    var filter =
        MediaFilter.builder().sortBy(OrderMediaBy.RUNTIME).sortDirection(SortOrder.ASC).build();

    var result = movieService.getMoviesWithFilter(10, null, 0, null, filter);
    var titles = result.getEdges().stream().map(e -> e.getNode().getTitle()).toList();

    assertThat(titles).containsExactly("With Runtime", "No Runtime");
  }

  @Test
  @DisplayName("Should paginate forward when sorted by release date")
  void shouldPaginateForwardWhenSortedByReleaseDate() {
    movieRepository.save(
        Movie.builder().title("First").releaseDate(LocalDate.of(2000, 1, 1)).build());
    movieRepository.save(
        Movie.builder().title("Second").releaseDate(LocalDate.of(2010, 1, 1)).build());
    movieRepository.save(
        Movie.builder().title("Third").releaseDate(LocalDate.of(2020, 1, 1)).build());

    var filter =
        MediaFilter.builder()
            .sortBy(OrderMediaBy.RELEASE_DATE)
            .sortDirection(SortOrder.ASC)
            .build();

    var firstPage = movieService.getMoviesWithFilter(1, null, 0, null, filter);
    assertThat(firstPage.getEdges().get(0).getNode().getTitle()).isEqualTo("First");
    assertThat(firstPage.getPageInfo().isHasNextPage()).isTrue();

    var cursor = firstPage.getPageInfo().getEndCursor().getValue();
    var secondPage = movieService.getMoviesWithFilter(1, cursor, 0, null, filter);
    assertThat(secondPage.getEdges().get(0).getNode().getTitle()).isEqualTo("Second");
  }

  @Test
  @DisplayName("Should publish MetadataEnrichedEvent when creating movie")
  void shouldPublishMetadataEnrichedEventWhenCreatingMovie() {
    var movie = Movie.builder().title("Inception").build();
    var imageSources = List.<ImageSource>of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg"));
    var metadataResult = new MetadataResult<>(movie, imageSources, Map.of(), Map.of());
    var mediaFile =
        MediaFile.builder()
            .filename("inception.mkv")
            .filepathUri("/movies/inception.mkv")
            .size(1000L)
            .build();

    movieService.createMovieWithAssociations(metadataResult, mediaFile);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityId()).isNotNull();
  }

  @Test
  @DisplayName("Should not publish event when image sources list is empty")
  void shouldNotPublishEventWhenImageSourcesListIsEmpty() {
    var movie = Movie.builder().title("Inception").build();
    var metadataResult = new MetadataResult<>(movie, List.of(), Map.of(), Map.of());
    var mediaFile =
        MediaFile.builder()
            .filename("inception.mkv")
            .filepathUri("/movies/inception.mkv")
            .size(1000L)
            .build();

    movieService.createMovieWithAssociations(metadataResult, mediaFile);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName("Should delete images for entity when deleting movie by ID")
  void shouldDeleteImagesForEntityWhenDeletingMovieById() {
    var movie = movieRepository.save(Movie.builder().title("Inception").build());
    seedImage(movie.getId());

    movieService.deleteMovieById(movie.getId());

    assertThat(imageRepository.findByEntityId(movie.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should delete images for each movie when deleting by library ID")
  void shouldDeleteImagesForEachMovieWhenDeletingByLibraryId() {
    var libraryId = UUID.randomUUID();
    var library = Library.builder().id(libraryId).name("Movies").build();
    var movie1 = movieRepository.save(Movie.builder().title("Movie 1").library(library).build());
    var movie2 = movieRepository.save(Movie.builder().title("Movie 2").library(library).build());
    seedImage(movie1.getId());
    seedImage(movie2.getId());

    movieService.deleteByLibraryId(libraryId);

    assertThat(imageRepository.findByEntityId(movie1.getId())).isEmpty();
    assertThat(imageRepository.findByEntityId(movie2.getId())).isEmpty();
  }

  @Test
  @DisplayName("Should overwrite scalar fields when refreshing movie metadata")
  void shouldOverwriteScalarFieldsWhenRefreshingMovieMetadata() {
    var existing =
        movieRepository.save(
            Movie.builder()
                .title("Old Title")
                .originalTitle("Old Original")
                .titleSort("old title")
                .tagline("Old tagline")
                .summary("Old summary")
                .runtime(90)
                .contentRating(new ContentRating("MPAA", "PG", "US"))
                .releaseDate(LocalDate.of(2000, 1, 1))
                .build());

    var fresh =
        Movie.builder()
            .title("New Title")
            .originalTitle("New Original")
            .titleSort("new title")
            .tagline("New tagline")
            .summary("New summary")
            .runtime(148)
            .contentRating(new ContentRating("MPAA", "PG-13", "US"))
            .releaseDate(LocalDate.of(2010, 7, 16))
            .build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    var result = movieService.refreshMovieMetadata(existing, metadataResult);

    assertThat(result.getTitle()).isEqualTo("New Title");
    assertThat(result.getOriginalTitle()).isEqualTo("New Original");
    assertThat(result.getTitleSort()).isEqualTo("new title");
    assertThat(result.getTagline()).isEqualTo("New tagline");
    assertThat(result.getSummary()).isEqualTo("New summary");
    assertThat(result.getRuntime()).isEqualTo(148);
    assertThat(result.getContentRating().value()).isEqualTo("PG-13");
    assertThat(result.getReleaseDate()).isEqualTo(LocalDate.of(2010, 7, 16));
    assertThat(result.getId()).isEqualTo(existing.getId());

    var persisted = movieRepository.findById(result.getId()).orElseThrow();
    assertThat(persisted.getTitle()).isEqualTo("New Title");
  }

  @Test
  @DisplayName("Should overwrite existing fields with null when fresh metadata has null fields")
  void shouldOverwriteExistingFieldsWithNullWhenFreshMetadataHasNullFields() {
    var existing =
        movieRepository.save(
            Movie.builder()
                .title("Inception")
                .tagline("Old tagline")
                .summary("Old summary")
                .contentRating(new ContentRating("MPAA", "PG", "US"))
                .build());

    var fresh = Movie.builder().title("Inception").titleSort("inception").build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    var result = movieService.refreshMovieMetadata(existing, metadataResult);

    assertThat(result.getTagline()).isNull();
    assertThat(result.getSummary()).isNull();
    assertThat(result.getContentRating()).isNull();
  }

  @Test
  @DisplayName("Should update associations when refreshing movie metadata")
  void shouldUpdateAssociationsWhenRefreshingMovieMetadata() {
    var existing = movieRepository.save(Movie.builder().title("Inception").build());

    var castInput = List.of(Person.builder().name("Leonardo DiCaprio").build());
    var directorInput = List.of(Person.builder().name("Christopher Nolan").build());
    var freshGenres = Set.of(Genre.builder().name("Sci-Fi").build());
    var freshStudios = Set.of(Company.builder().name("Warner Bros.").build());

    when(personService.getOrCreatePersons(
            argThat(
                persons ->
                    persons != null
                        && persons.stream().anyMatch(p -> "Leonardo DiCaprio".equals(p.getName()))),
            any()))
        .thenReturn(castInput);
    when(personService.getOrCreatePersons(
            argThat(
                persons ->
                    persons != null
                        && persons.stream().anyMatch(p -> "Christopher Nolan".equals(p.getName()))),
            any()))
        .thenReturn(directorInput);
    when(genreService.getOrCreateGenres(any())).thenReturn(freshGenres);
    when(companyService.getOrCreateCompanies(any(), any())).thenReturn(freshStudios);

    var fresh = Movie.builder().title("Inception").cast(castInput).directors(directorInput).build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    var result = movieService.refreshMovieMetadata(existing, metadataResult);

    assertThat(result.getCast()).extracting(Person::getName).containsExactly("Leonardo DiCaprio");
    assertThat(result.getDirectors())
        .extracting(Person::getName)
        .containsExactly("Christopher Nolan");
    assertThat(result.getGenres()).extracting(Genre::getName).containsExactly("Sci-Fi");
    assertThat(result.getStudios()).extracting(Company::getName).containsExactly("Warner Bros.");
  }

  @Test
  @DisplayName("Should publish image event when refreshing movie metadata with image sources")
  void shouldPublishImageEventWhenRefreshingMovieMetadataWithImageSources() {
    var existing = movieRepository.save(Movie.builder().title("Inception").build());
    var imageSources = List.<ImageSource>of(new TmdbImageSource(ImageType.POSTER, "/poster.jpg"));
    var fresh = Movie.builder().title("Inception").build();
    var metadataResult = new MetadataResult<>(fresh, imageSources, Map.of(), Map.of());

    movieService.refreshMovieMetadata(existing, metadataResult);

    var events = eventPublisher.getEventsOfType(MetadataEnrichedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().entityId()).isEqualTo(existing.getId());
    assertThat(events.getFirst().entityType()).isEqualTo(ImageEntityType.MOVIE);
  }

  @Test
  @DisplayName("Should not publish image event when refreshing movie metadata with empty sources")
  void shouldNotPublishImageEventWhenRefreshingMovieMetadataWithEmptySources() {
    var existing = movieRepository.save(Movie.builder().title("Inception").build());
    var fresh = Movie.builder().title("Inception").build();
    var metadataResult = new MetadataResult<>(fresh, List.of(), Map.of(), Map.of());

    movieService.refreshMovieMetadata(existing, metadataResult);

    assertThat(eventPublisher.getEventsOfType(MetadataEnrichedEvent.class)).isEmpty();
  }

  private void seedImage(UUID entityId) {
    imageRepository.save(
        Image.builder()
            .entityId(entityId)
            .entityType(ImageEntityType.MOVIE)
            .imageType(ImageType.POSTER)
            .variant(ImageSize.SMALL)
            .width(185)
            .height(278)
            .path("movie/" + entityId + "/poster/small.jpg")
            .build());
  }
}
