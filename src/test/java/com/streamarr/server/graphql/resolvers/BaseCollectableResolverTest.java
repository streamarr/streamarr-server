package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.support.security.WithProfileContext;
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
@WithProfileContext
@SpringBootTest(classes = {MovieResolver.class, SecurityContextAuthorizationService.class})
@DisplayName("Movie Resolver Tests - Files Field")
class BaseCollectableResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private ProfileRepository profileRepository;

  @MockitoBean private AccountProfileRepository accountProfileRepository;

  @MockitoBean private MovieService movieService;

  @Test
  @DisplayName("Should return files when movie queried with files field")
  void shouldReturnFilesWhenMovieQueriedWithFilesField() {
    var movieId = UUID.randomUUID();
    var movie = Movie.builder().title("Inception").build();
    movie.setId(movieId);

    when(movieService.findById(movieId)).thenReturn(Optional.of(movie));
    when(movieService.findMediaFiles(movieId))
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
