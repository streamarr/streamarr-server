package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.Map;
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
@WithProfileContext
@SpringBootTest(classes = {MovieResolver.class, SecurityContextAuthorizationService.class})
@DisplayName("Movie Resolver Tests")
class MovieResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private ProfileRepository profileRepository;

  @MockitoBean private AccountProfileRepository accountProfileRepository;

  @MockitoBean private MovieService movieService;

  @Test
  @DisplayName("Should return movie when valid ID provided")
  void shouldReturnMovieWhenValidIdProvided() {
    var movieId = UUID.randomUUID();
    var movie =
        Movie.builder().title("Inception").tagline("Your mind is the scene of the crime.").build();
    movie.setId(movieId);

    when(movieService.findById(movieId)).thenReturn(Optional.of(movie));

    String title =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { title tagline } }", movieId), "data.movie.title");

    assertThat(title).isEqualTo("Inception");
  }

  @Test
  @DisplayName("Should return background-created movie when creator absent")
  @SuppressWarnings("unchecked")
  void shouldReturnBackgroundCreatedMovieWhenCreatorAbsent() {
    var movieId = UUID.randomUUID();
    var movie = Movie.builder().title("Background Movie").build();
    movie.setId(movieId);

    when(movieService.findById(movieId)).thenReturn(Optional.of(movie));

    var result =
        dgsQueryExecutor.execute(
            String.format("{ movie(id: \"%s\") { title createdBy } }", movieId));

    assertThat(result.getErrors()).isEmpty();
    var data = result.<Map<String, Object>>getData();
    var movieData = (Map<String, Object>) data.get("movie");
    assertThat(movieData)
        .containsEntry("title", "Background Movie")
        .containsEntry("createdBy", null);
  }

  @Test
  @DisplayName("Should return null when movie not found")
  void shouldReturnNullWhenMovieNotFound() {
    when(movieService.findById(any(UUID.class))).thenReturn(Optional.empty());

    Object result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { title } }", UUID.randomUUID()), "data.movie");

    assertThat(result).isNull();
  }

  @Test
  @DisplayName("Should return error when invalid ID provided")
  void shouldReturnErrorWhenInvalidIdProvided() {
    var result = dgsQueryExecutor.execute("{ movie(id: \"not-a-uuid\") { title } }");

    assertThat(result.getErrors()).isNotEmpty();
    assertThat(result.getErrors().get(0).getMessage()).contains("Invalid ID format");
  }
}
