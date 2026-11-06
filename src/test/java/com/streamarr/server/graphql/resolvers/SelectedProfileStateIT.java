package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
@DisplayName("Selected Profile State Integration Tests")
class SelectedProfileStateIT extends AbstractIntegrationTest {

  private static final String PROFILE_FIELDS =
      "id name picture kind personal pinConfigured locked selected";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper mapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MovieRepository movieRepository;

  private AuthTestSupport.TestIdentity identity;

  @AfterEach
  void deleteIdentity() {
    if (identity != null) {
      authTestSupport.deleteIdentity(identity);
    }
  }

  @Test
  @DisplayName(
      "Should return the same Profile as Me.selectedProfile when a ServerAdmin reads a movie with a"
          + " selected Profile")
  void shouldReturnTheSameProfileAsMeSelectedProfileWhenAServerAdminReadsAMovieWithAProfile()
      throws Exception {
    identity = authTestSupport.createAdminIdentity();
    var movie = createMovie();

    var response =
        query(
            """
            {
              me { selectedProfile { %1$s } }
              movie(id: "%2$s") { selectedProfileState { profile { %1$s } } }
            }
            """
                .formatted(PROFILE_FIELDS, movie.getId()));

    assertThat(response.has("errors")).as("errors in %s", response).isFalse();
    assertThat(response.at("/data/movie/selectedProfileState/profile/id").asString())
        .isEqualTo(identity.profile().getId().toString());
    assertThat(response.at("/data/movie/selectedProfileState/profile"))
        .isEqualTo(response.at("/data/me/selectedProfile"));
  }

  private JsonNode query(String document) throws Exception {
    var body =
        mockMvc
            .perform(graphqlRequest(authTestSupport.profileBearer(identity), document))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(body);
  }

  private Movie createMovie() {
    var library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeLibrary());
    return movieRepository.saveAndFlush(
        Movie.builder()
            .title("Selected Profile Movie")
            .titleSort("Selected Profile Movie")
            .library(library)
            .build());
  }
}
