package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.media.MediaFileRepository;
import com.streamarr.server.repositories.media.MovieRepository;
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
@SpringBootTest(classes = {MovieResolver.class})
@DisplayName("Movie Resolver Tests - Files Field")
class BaseCollectableResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private MovieRepository movieRepository;

  @MockitoBean private MediaFileRepository mediaFileRepository;

  @Test
  @DisplayName("Should return files when movie queried with files field")
  void shouldReturnFilesWhenMovieQueriedWithFilesField() {
    var movieId = UUID.randomUUID();
    var movie = Movie.builder().title("Inception").build();
    movie.setId(movieId);

    when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));
    when(mediaFileRepository.findByMediaId(movieId))
        .thenReturn(
            List.of(
                MediaFile.builder()
                    .filename("inception.mkv")
                    .filepathUri("/movies/inception.mkv")
                    .size(1_500_000_000L)
                    .build()));

    String filename =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { files { filename } } }", movieId),
            "data.movie.files[0].filename");

    assertThat(filename).isEqualTo("inception.mkv");
  }
}
