package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.media.MovieRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(classes = {MovieResolver.class})
@DisplayName("Movie Resolver Tests")
class MovieResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private MovieRepository movieRepository;

  @Test
  @DisplayName("Should return movie when valid ID provided")
  void shouldReturnMovieWhenValidIdProvided() {
    var movieId = UUID.randomUUID();
    var movie =
        Movie.builder()
            .title("Inception")
            .tagline("Your mind is the scene of the crime.")
            .build();
    movie.setId(movieId);

    when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));

    String title =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { title tagline } }", movieId),
            "data.movie.title");

    assertThat(title).isEqualTo("Inception");
  }

  @Test
  @DisplayName("Should return null when movie not found")
  void shouldReturnNullWhenMovieNotFound() {
    when(movieRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

    Object result =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { title } }", UUID.randomUUID()),
            "data.movie");

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
