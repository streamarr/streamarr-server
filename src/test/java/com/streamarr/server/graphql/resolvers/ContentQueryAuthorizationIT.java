package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.AuthTestSupport.bearer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.MovieRepository;
import com.streamarr.server.support.AuthTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Content is profile-scoped (ADR 0015: account- and household-scoped tokens cannot reach content)
 * — direct content queries demand a selected profile and answer PROFILE_REQUIRED otherwise.
 */
@Tag("IntegrationTest")
@DisplayName("Content Query Authorization Integration Tests")
class ContentQueryAuthorizationIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private AuthTestSupport authTestSupport;

  @Autowired private LibraryRepository libraryRepository;

  @Autowired private MovieRepository movieRepository;

  private AuthTestSupport.TestIdentity identity;
  private Movie movie;

  @AfterEach
  void cleanUp() {
    if (movie != null) {
      movieRepository.deleteById(movie.getId());
      libraryRepository.deleteById(movie.getLibrary().getId());
      movie = null;
    }
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName("Should deny movie query when no profile selected")
  void shouldDenyMovieQueryWhenNoProfileSelected() throws Exception {
    identity = authTestSupport.createIdentity();
    movie = seedMovie();

    postGraphQl(movieQuery(), authTestSupport.accountBearer(identity))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("PROFILE_REQUIRED"))
        .andExpect(jsonPath("$.data.movie.title").doesNotExist());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{ series(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") { title } }",
        "{ season(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") { seasonNumber } }",
        "{ episode(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") { title } }",
        "{ rating(id: \\\"58edcb42-4c93-4a05-876f-c48e0c48ff30\\\") { source } }"
      })
  @DisplayName("Should deny content query when no profile selected")
  void shouldDenyContentQueryWhenNoProfileSelected(String query) throws Exception {
    identity = authTestSupport.createIdentity();

    postGraphQl(query, authTestSupport.accountBearer(identity))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("PROFILE_REQUIRED"));
  }

  @Test
  @DisplayName("Should return movie when profile selected")
  void shouldReturnMovieWhenProfileSelected() throws Exception {
    identity = authTestSupport.createIdentity();
    movie = seedMovie();

    postGraphQl(movieQuery(), authTestSupport.profileBearer(identity))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.movie.title").value(movie.getTitle()));
  }

  private String movieQuery() {
    return "{ movie(id: \\\"%s\\\") { title } }".formatted(movie.getId());
  }

  private Movie seedMovie() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository.saveAndFlush(
        Movie.builder().title("Profile Gate Movie").library(library).build());
  }

  private ResultActions postGraphQl(String query, String token) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"query\": \"%s\"}".formatted(query))
            .with(bearer(token)));
  }
}
