package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.exceptions.UnsupportedMediaTypeException;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.CursorValidator;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.library.LibraryManagementService;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.MediaPaginationOptions;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.SortOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      LibraryResolver.class,
      PaginationService.class,
      CursorUtil.class,
      CursorValidator.class,
      RelayConnectionAdapter.class,
      JacksonAutoConfiguration.class
    })
@DisplayName("Library Resolver Tests")
class LibraryResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private LibraryRepository libraryRepository;

  @MockitoBean private LibraryManagementService libraryManagementService;

  @MockitoBean private MovieService movieService;

  @MockitoBean private SeriesService seriesService;

  @Nested
  @DisplayName("Library Queries")
  class LibraryQueries {

    @Test
    @DisplayName("Should return library when valid ID provided")
    void shouldReturnLibraryWhenValidIdProvided() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      String name =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ library(id: \"%s\") { name filepathUri } }", libraryId),
              "data.library.name");

      assertThat(name).isEqualTo("Movies");
    }

    @Test
    @DisplayName("Should return all libraries when queried")
    void shouldReturnAllLibrariesWhenQueried() {
      var moviesLibrary =
          Library.builder()
              .name("Movies")
              .filepathUri("file:///mpool/media/movies")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.MOVIE)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();

      var showsLibrary =
          Library.builder()
              .name("TV Shows")
              .filepathUri("file:///mpool/media/shows")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.SERIES)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();

      when(libraryRepository.findAll()).thenReturn(List.of(moviesLibrary, showsLibrary));

      List<String> names =
          dgsQueryExecutor.executeAndExtractJsonPath(
              "{ libraries { name } }", "data.libraries[*].name");

      assertThat(names).containsExactly("Movies", "TV Shows");
    }
  }

  @Nested
  @DisplayName("Library Mutations")
  class LibraryMutations {

    @Test
    @DisplayName("Should return true when scanLibrary called with valid ID")
    void shouldReturnTrueWhenScanLibraryCalledWithValidId() {
      Boolean result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("mutation { scanLibrary(id: \"%s\") }", UUID.randomUUID()),
              "data.scanLibrary");

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return true when refreshLibrary called with valid ID")
    void shouldReturnTrueWhenRefreshLibraryCalledWithValidId() {
      Boolean result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("mutation { refreshLibrary(id: \"%s\") }", UUID.randomUUID()),
              "data.refreshLibrary");

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return library when addLibrary called with valid input")
    void shouldReturnLibraryWhenAddLibraryCalledWithValidInput() {
      var library =
          Library.builder()
              .name("Movies")
              .filepathUri("file:///mpool/media/movies")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.MOVIE)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();
      library.setId(UUID.randomUUID());

      when(libraryManagementService.addLibrary(any(Library.class))).thenReturn(library);

      String name =
          dgsQueryExecutor.executeAndExtractJsonPath(
              """
              mutation {
                addLibrary(input: {
                  name: "Movies"
                  filepath: "/mpool/media/movies"
                  type: MOVIE
                  backend: LOCAL
                  externalAgentStrategy: TMDB
                }) { name filepathUri }
              }
              """,
              "data.addLibrary.name");

      assertThat(name).isEqualTo("Movies");
    }

    @Test
    @DisplayName("Should return true when removeLibrary called with valid ID")
    void shouldReturnTrueWhenRemoveLibraryCalledWithValidId() {
      Boolean result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("mutation { removeLibrary(id: \"%s\") }", UUID.randomUUID()),
              "data.removeLibrary");

      assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should return error when removeLibrary called with invalid ID")
    void shouldReturnErrorWhenRemoveLibraryCalledWithInvalidId() {
      var result = dgsQueryExecutor.execute("mutation { removeLibrary(id: \"not-a-uuid\") }");

      assertThat(result.getErrors()).isNotEmpty();
      assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
    }

    @ParameterizedTest(name = "Should return error when {0} called with invalid ID")
    @MethodSource("com.streamarr.server.graphql.resolvers.LibraryResolverTest#invalidIdOperations")
    @DisplayName("Should return error when operation called with invalid ID")
    void shouldReturnErrorWhenCalledWithInvalidId(String operationName, String query) {
      var result = dgsQueryExecutor.execute(query);

      assertThat(result.getErrors()).isNotEmpty();
      assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
    }
  }

  static Stream<Arguments> invalidIdOperations() {
    return Stream.of(
        Arguments.of("library", "{ library(id: \"not-a-uuid\") { name } }"),
        Arguments.of("refreshLibrary", "mutation { refreshLibrary(id: \"not-a-uuid\") }"),
        Arguments.of("removeLibrary", "mutation { removeLibrary(id: \"not-a-uuid\") }"));
  }

  @Nested
  @DisplayName("Paginated Items")
  class PaginatedItems {

    @Test
    @DisplayName("Should return paginated items when library queried")
    void shouldReturnPaginatedItemsWhenLibraryQueried() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Inception").titleSort("Inception").build();
      movie.setId(UUID.randomUUID());

      var page = new MediaPage<>(List.of(new PageItem<>(movie, "Inception")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(movieService.getMoviesWithFilter(any(MediaPaginationOptions.class))).thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10) { edges { node { ... on Movie { title } } cursor } pageInfo { hasNextPage } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Inception");
    }

    @Test
    @DisplayName("Should return series items when series library queried")
    void shouldReturnSeriesItemsWhenSeriesLibraryQueried() {
      var libraryId = UUID.randomUUID();
      var library =
          Library.builder()
              .name("TV Shows")
              .filepathUri("file:///mpool/media/shows")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.SERIES)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();
      library.setId(libraryId);

      var series = Series.builder().title("Breaking Bad").titleSort("Breaking Bad").build();
      series.setId(UUID.randomUUID());

      var page = new MediaPage<>(List.of(new PageItem<>(series, "Breaking Bad")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(seriesService.getSeriesWithFilter(any(MediaPaginationOptions.class))).thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10) { edges { node { ... on Series { title } } cursor } pageInfo { hasNextPage } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Breaking Bad");
    }

    @Test
    @DisplayName("Should return error when unsupported media type in items")
    void shouldReturnErrorWhenUnsupportedMediaTypeInItems() {
      var libraryId = UUID.randomUUID();
      var library =
          Library.builder()
              .name("Other Media")
              .filepathUri("file:///mpool/media/other")
              .status(LibraryStatus.HEALTHY)
              .backend(LibraryBackend.LOCAL)
              .type(MediaType.OTHER)
              .externalAgentStrategy(ExternalAgentStrategy.TMDB)
              .build();
      library.setId(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      var result =
          dgsQueryExecutor.execute(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId));

      assertThat(result.getErrors()).isNotEmpty();
      assertThat(result.getErrors().get(0).getMessage()).contains("Unsupported media type");
    }

    @Test
    @DisplayName("Should return GraphQL error when cursor is malformed")
    void shouldReturnGraphQLErrorWhenCursorIsMalformed() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      var result =
          dgsQueryExecutor.execute(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10, after: "not-a-valid-cursor") { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId));

      assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    @DisplayName("Should delegate sort options to movie service when sort input provided")
    void shouldDelegateSortOptionsToMovieServiceWhenSortInputProvided() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Inception").titleSort("Inception").build();
      movie.setId(UUID.randomUUID());

      var page = new MediaPage<>(List.of(new PageItem<>(movie, "Inception")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      when(movieService.getMoviesWithFilter(
              argThat(
                  (MediaPaginationOptions opts) -> {
                    var f = opts.getMediaFilter();
                    return f.getSortBy()
                            == com.streamarr.server.services.pagination.OrderMediaBy.ADDED
                        && f.getSortDirection() == SortOrder.DESC;
                  })))
          .thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10, sort: {by: ADDED, direction: DESC}) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Inception");
    }

    @Test
    @DisplayName("Should delegate filter options to movie service when filter input provided")
    void shouldDelegateFilterOptionsToMovieServiceWhenFilterInputProvided() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var movie = Movie.builder().title("Filtered Movie").titleSort("Filtered Movie").build();
      movie.setId(UUID.randomUUID());

      var page = new MediaPage<>(List.of(new PageItem<>(movie, "Filtered Movie")), false, false);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

      when(movieService.getMoviesWithFilter(
              argThat(
                  (MediaPaginationOptions opts) -> {
                    var f = opts.getMediaFilter();
                    return f.getStartLetter() == AlphabetLetter.A
                        && f.getYears() != null
                        && f.getYears().contains(2024)
                        && f.getContentRatings() != null
                        && f.getContentRatings().contains("PG-13")
                        && Boolean.FALSE.equals(f.getUnmatched());
                  })))
          .thenReturn(page);

      String title =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  """
                  { library(id: "%s") { items(first: 10, filter: {startLetter: A, years: [2024], contentRatings: ["PG-13"], unmatched: false}) { edges { node { ... on Movie { title } } } } } }
                  """,
                  libraryId),
              "data.library.items.edges[0].node.title");

      assertThat(title).isEqualTo("Filtered Movie");
    }

    @Test
    @DisplayName("Should throw with simple class name when unsupported media type in type resolver")
    void shouldThrowWithSimpleClassNameWhenUnsupportedMediaTypeInTypeResolver() {
      var resolver = new LibraryResolver(null, null, null, null, null, null, null, null);

      var unsupportedMedia = new Object();

      assertThatThrownBy(() -> resolver.resolveMedia(unsupportedMedia))
          .isInstanceOf(UnsupportedMediaTypeException.class)
          .hasMessage("Unsupported media type: Object");
    }
  }

  @Nested
  @DisplayName("Alphabet Index")
  class AlphabetIndexTests {

    @Test
    @DisplayName("Should return alphabet index when library exists")
    void shouldReturnAlphabetIndexWhenLibraryExists() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      var metadataA =
          LibraryMetadata.builder()
              .libraryId(libraryId)
              .letter(AlphabetLetter.A)
              .itemCount(5)
              .build();
      metadataA.setId(UUID.randomUUID());

      var metadataM =
          LibraryMetadata.builder()
              .libraryId(libraryId)
              .letter(AlphabetLetter.M)
              .itemCount(12)
              .build();
      metadataM.setId(UUID.randomUUID());

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(libraryManagementService.getAlphabetIndex(libraryId))
          .thenReturn(List.of(metadataA, metadataM));

      var query =
          String.format("{ library(id: \"%s\") { alphabetIndex { letter count } } }", libraryId);

      List<String> letters =
          dgsQueryExecutor.executeAndExtractJsonPath(query, "data.library.alphabetIndex[*].letter");
      List<Integer> counts =
          dgsQueryExecutor.executeAndExtractJsonPath(query, "data.library.alphabetIndex[*].count");

      assertThat(letters).containsExactly("A", "M");
      assertThat(counts).containsExactly(5, 12);
    }

    @Test
    @DisplayName("Should return empty alphabet index when no metadata")
    void shouldReturnEmptyAlphabetIndexWhenNoMetadata() {
      var libraryId = UUID.randomUUID();
      var library = buildMovieLibrary(libraryId);

      when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
      when(libraryManagementService.getAlphabetIndex(libraryId)).thenReturn(List.of());

      var query =
          String.format("{ library(id: \"%s\") { alphabetIndex { letter count } } }", libraryId);

      List<Object> alphabetIndex =
          dgsQueryExecutor.executeAndExtractJsonPath(query, "data.library.alphabetIndex");

      assertThat(alphabetIndex).isEmpty();
    }
  }

  private Library buildMovieLibrary(UUID libraryId) {
    var library =
        Library.builder()
            .name("Movies")
            .filepathUri("file:///mpool/media/movies")
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();
    library.setId(libraryId);
    return library;
  }
}
