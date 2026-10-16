package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.ExternalIdentifier;
import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * A client places an item it received outside a library view, such as from Continue Watching or a
 * watch-state change, by the item's library and by whether a metadata provider matched it.
 */
@Tag("IntegrationTest")
@DisplayName("Library Placement Fields Integration Tests")
class LibraryPlacementFieldsIT extends AbstractIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;
  @Autowired private SeriesRepository seriesRepository;

  private AuthTestSupport.TestIdentity identity;

  @BeforeEach
  void createIdentity() {
    identity = authTestSupport.createIdentity();
  }

  @AfterEach
  void deleteIdentity() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should return the owning library when a movie is queried outside its library")
  void shouldReturnOwningLibraryWhenMovieIsQueriedOutsideItsLibrary() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie = saveMovie(library, Set.of());

    query("{ movie(id: \"%s\") { library { id name } } }".formatted(movie.getId()))
        .andExpect(jsonPath("$.data.movie.library.id").value(library.getId().toString()))
        .andExpect(jsonPath("$.data.movie.library.name").value(library.getName()));
  }

  @Test
  @DisplayName("Should return unmatched true when a movie has no provider id")
  void shouldReturnUnmatchedTrueWhenMovieHasNoProviderId() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie = saveMovie(library, Set.of());

    query("{ movie(id: \"%s\") { unmatched } }".formatted(movie.getId()))
        .andExpect(jsonPath("$.data.movie.unmatched").value(true));
  }

  @Test
  @DisplayName("Should return unmatched false when a movie has a provider id")
  void shouldReturnUnmatchedFalseWhenMovieHasProviderId() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var movie = saveMovie(library, Set.of(tmdbId()));

    query("{ movie(id: \"%s\") { unmatched } }".formatted(movie.getId()))
        .andExpect(jsonPath("$.data.movie.unmatched").value(false));
  }

  @Test
  @DisplayName("Should return the owning library and unmatched for each series")
  void shouldReturnOwningLibraryAndUnmatchedForEachSeries() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    var unmatched = saveSeries(library, Set.of());
    var matched = saveSeries(library, Set.of(tmdbId()));

    query(
            """
            {
              unmatched: series(id: "%s") { library { id } unmatched }
              matched: series(id: "%s") { library { id } unmatched }
            }
            """
                .formatted(unmatched.getId(), matched.getId()))
        .andExpect(jsonPath("$.data.unmatched.library.id").value(library.getId().toString()))
        .andExpect(jsonPath("$.data.unmatched.unmatched").value(true))
        .andExpect(jsonPath("$.data.matched.library.id").value(library.getId().toString()))
        .andExpect(jsonPath("$.data.matched.unmatched").value(false));
  }

  @Test
  @DisplayName("Should agree with the unmatched filter when a library lists its movies")
  void shouldAgreeWithUnmatchedFilterWhenLibraryListsItsMovies() throws Exception {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    var unmatched = saveMovie(library, Set.of());
    saveMovie(library, Set.of(tmdbId()));

    query(
            """
            {
              library(id: "%s") {
                items(first: 10, filter: { unmatched: true }) {
                  edges { node { ... on Movie { id unmatched } } }
                }
              }
            }
            """
                .formatted(library.getId()))
        .andExpect(jsonPath("$.data.library.items.edges.length()").value(1))
        .andExpect(
            jsonPath("$.data.library.items.edges[0].node.id").value(unmatched.getId().toString()))
        .andExpect(jsonPath("$.data.library.items.edges[0].node.unmatched").value(true));
  }

  private ResultActions query(String document) throws Exception {
    return mockMvc
        .perform(
            post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("query", document)))
                .with(bearer(authTestSupport.profileBearer(identity))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist());
  }

  private Movie saveMovie(Library library, Set<ExternalIdentifier> externalIds) {
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Placed Movie " + UUID.randomUUID())
            .titleSort("Placed Movie")
            .externalIds(externalIds)
            .library(library)
            .build());
  }

  private Series saveSeries(Library library, Set<ExternalIdentifier> externalIds) {
    return seriesRepository.saveAndFlush(
        Series.builder()
            .title("Placed Series " + UUID.randomUUID())
            .titleSort("Placed Series")
            .externalIds(externalIds)
            .library(library)
            .build());
  }

  private static ExternalIdentifier tmdbId() {
    return ExternalIdentifier.builder()
        .externalId(UUID.randomUUID().toString())
        .externalSourceType(ExternalSourceType.TMDB)
        .build();
  }
}
