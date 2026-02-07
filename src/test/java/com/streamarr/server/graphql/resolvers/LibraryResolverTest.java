package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.ExternalAgentStrategy;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryBackend;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.domain.media.MediaType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.library.LibraryManagementService;
import graphql.relay.DefaultConnection;
import graphql.relay.DefaultConnectionCursor;
import graphql.relay.DefaultEdge;
import graphql.relay.DefaultPageInfo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {LibraryResolver.class})
@DisplayName("Library Resolver Tests")
class LibraryResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private LibraryRepository libraryRepository;

  @MockitoBean private LibraryManagementService libraryManagementService;

  @MockitoBean private MovieService movieService;

  @MockitoBean private SeriesService seriesService;

  @Test
  @DisplayName("Should return library when valid ID provided")
  void shouldReturnLibraryWhenValidIdProvided() {
    var libraryId = UUID.randomUUID();
    var library =
        Library.builder()
            .name("Movies")
            .filepath("/mpool/media/movies")
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();
    library.setId(libraryId);

    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));

    String name =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ library(id: \"%s\") { name filepath } }", libraryId),
            "data.library.name");

    assertThat(name).isEqualTo("Movies");
  }

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
  @DisplayName("Should return error when invalid ID provided")
  void shouldReturnErrorWhenInvalidIdProvided() {
    var result = dgsQueryExecutor.execute("{ library(id: \"not-a-uuid\") { name } }");

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
  }

  @Test
  @DisplayName("Should return paginated items when library queried")
  void shouldReturnPaginatedItemsWhenLibraryQueried() {
    var libraryId = UUID.randomUUID();
    var library =
        Library.builder()
            .name("Movies")
            .filepath("/mpool/media/movies")
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();
    library.setId(libraryId);

    var movie = Movie.builder().title("Inception").build();
    movie.setId(UUID.randomUUID());

    var cursor = new DefaultConnectionCursor("cursor1");
    var connection =
        new DefaultConnection<>(
            List.of(new DefaultEdge<>(movie, cursor)),
            new DefaultPageInfo(cursor, cursor, false, false));

    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    doReturn(connection)
        .when(movieService)
        .getMoviesWithFilter(anyInt(), any(), anyInt(), any(), any());

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
  @DisplayName("Should return all libraries when queried")
  void shouldReturnAllLibrariesWhenQueried() {
    var moviesLibrary =
        Library.builder()
            .name("Movies")
            .filepath("/mpool/media/movies")
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .type(MediaType.MOVIE)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();

    var showsLibrary =
        Library.builder()
            .name("TV Shows")
            .filepath("/mpool/media/shows")
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

  @Test
  @DisplayName("Should return series items when series library queried")
  void shouldReturnSeriesItemsWhenSeriesLibraryQueried() {
    var libraryId = UUID.randomUUID();
    var library =
        Library.builder()
            .name("TV Shows")
            .filepath("/mpool/media/shows")
            .status(LibraryStatus.HEALTHY)
            .backend(LibraryBackend.LOCAL)
            .type(MediaType.SERIES)
            .externalAgentStrategy(ExternalAgentStrategy.TMDB)
            .build();
    library.setId(libraryId);

    var series = Series.builder().title("Breaking Bad").build();
    series.setId(UUID.randomUUID());

    var cursor = new DefaultConnectionCursor("cursor1");
    var connection =
        new DefaultConnection<>(
            List.of(new DefaultEdge<>(series, cursor)),
            new DefaultPageInfo(cursor, cursor, false, false));

    when(libraryRepository.findById(libraryId)).thenReturn(Optional.of(library));
    doReturn(connection)
        .when(seriesService)
        .getSeriesWithFilter(anyInt(), any(), anyInt(), any(), any());

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
            .filepath("/mpool/media/other")
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
}
