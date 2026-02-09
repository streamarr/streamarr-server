package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.dataloaders.ImageDataLoader;
import com.streamarr.server.repositories.media.ImageRepository;
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
@SpringBootTest(classes = {ImageFieldResolver.class, ImageDataLoader.class, MovieResolver.class})
@DisplayName("Image Field Resolver Tests")
class ImageFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private ImageRepository imageRepository;
  @MockitoBean private MovieRepository movieRepository;
  @MockitoBean private MediaFileRepository mediaFileRepository;

  @Test
  @DisplayName("Should load images via DataLoader when resolving movie images")
  void shouldLoadImagesViaDataLoaderWhenResolvingMovieImages() {
    var movie = setupMovie();
    var image = buildImage(movie.getId(), ImageType.POSTER, ImageSize.SMALL, 185, 278);
    when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
        .thenReturn(List.of(image));

    String imageType =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { images { imageType } } }", movie.getId()),
            "data.movie.images[0].imageType");

    assertThat(imageType).isEqualTo("POSTER");
  }

  @Test
  @DisplayName("Should filter by image type when type argument provided")
  void shouldFilterByImageTypeWhenTypeArgumentProvided() {
    var movie = setupMovie();
    var posterImage = buildImage(movie.getId(), ImageType.POSTER, ImageSize.SMALL, 185, 278);
    var backdropImage = buildImage(movie.getId(), ImageType.BACKDROP, ImageSize.SMALL, 300, 169);
    when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
        .thenReturn(List.of(posterImage, backdropImage));

    List<String> imageTypes =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format(
                "{ movie(id: \"%s\") { images(type: POSTER) { imageType } } }", movie.getId()),
            "data.movie.images[*].imageType");

    assertThat(imageTypes).containsExactly("POSTER");
  }

  @Test
  @DisplayName("Should return all images when type argument omitted")
  void shouldReturnAllImagesWhenTypeArgumentOmitted() {
    var movie = setupMovie();
    var posterImage = buildImage(movie.getId(), ImageType.POSTER, ImageSize.SMALL, 185, 278);
    var backdropImage = buildImage(movie.getId(), ImageType.BACKDROP, ImageSize.SMALL, 300, 169);
    when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
        .thenReturn(List.of(posterImage, backdropImage));

    List<String> imageTypes =
        dgsQueryExecutor.executeAndExtractJsonPath(
            String.format("{ movie(id: \"%s\") { images { imageType } } }", movie.getId()),
            "data.movie.images[*].imageType");

    assertThat(imageTypes).containsExactlyInAnyOrder("POSTER", "BACKDROP");
  }

  private Movie setupMovie() {
    var movieId = UUID.randomUUID();
    var movie = Movie.builder().title("Inception").build();
    movie.setId(movieId);
    when(movieRepository.findById(movieId)).thenReturn(Optional.of(movie));
    return movie;
  }

  private Image buildImage(
      UUID entityId, ImageType imageType, ImageSize variant, int width, int height) {
    var image =
        Image.builder()
            .entityId(entityId)
            .entityType(ImageEntityType.MOVIE)
            .imageType(imageType)
            .variant(variant)
            .width(width)
            .height(height)
            .path("test/path.jpg")
            .build();
    image.setId(UUID.randomUUID());
    return image;
  }
}
