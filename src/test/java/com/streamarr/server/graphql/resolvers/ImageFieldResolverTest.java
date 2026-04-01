package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.graphql.dataloaders.ImageDataLoader;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      ImageFieldResolver.class,
      ImageDataLoader.class,
      MovieResolver.class,
      SeriesResolver.class,
      SeriesFieldResolver.class,
      SeasonFieldResolver.class,
    })
@DisplayName("Image Field Resolver Tests")
class ImageFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private ImageRepository imageRepository;
  @MockitoBean private MovieService movieService;
  @MockitoBean private SeriesService seriesService;

  @Nested
  @DisplayName("Movie Images")
  class MovieImages {

    @Test
    @DisplayName("Should load images via DataLoader when resolving movie images")
    void shouldLoadImagesViaDataLoaderWhenResolvingMovieImages() {
      var movie = setupMovie();
      var image =
          buildImage(
              movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.SMALL, 185, 278);
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
      var posterImage =
          buildImage(
              movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.SMALL, 185, 278);
      var backdropImage =
          buildImage(
              movie.getId(), ImageEntityType.MOVIE, ImageType.BACKDROP, ImageSize.SMALL, 300, 169);
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
      var posterImage =
          buildImage(
              movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER, ImageSize.SMALL, 185, 278);
      var backdropImage =
          buildImage(
              movie.getId(), ImageEntityType.MOVIE, ImageType.BACKDROP, ImageSize.SMALL, 300, 169);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
          .thenReturn(List.of(posterImage, backdropImage));

      List<String> imageTypes =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ movie(id: \"%s\") { images { imageType } } }", movie.getId()),
              "data.movie.images[*].imageType");

      assertThat(imageTypes).containsExactlyInAnyOrder("POSTER", "BACKDROP");
    }
  }

  @Nested
  @DisplayName("Series Images")
  class SeriesImages {

    @Test
    @DisplayName("Should load images when resolving series images")
    void shouldLoadImagesWhenResolvingSeriesImages() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Breaking Bad").build();
      series.setId(seriesId);
      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));

      var image =
          buildImage(seriesId, ImageEntityType.SERIES, ImageType.POSTER, ImageSize.SMALL, 185, 278);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.SERIES), any()))
          .thenReturn(List.of(image));

      String imageType =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { images { imageType } } }", seriesId),
              "data.series.images[0].imageType");

      assertThat(imageType).isEqualTo("POSTER");
    }
  }

  @Nested
  @DisplayName("Season Images")
  class SeasonImages {

    @Test
    @DisplayName("Should load images when resolving season images")
    void shouldLoadImagesWhenResolvingSeasonImages() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Breaking Bad").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).build();
      season.setId(seasonId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));

      var image =
          buildImage(seasonId, ImageEntityType.SEASON, ImageType.POSTER, ImageSize.SMALL, 185, 278);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.SEASON), any()))
          .thenReturn(List.of(image));

      String imageType =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { images { imageType } } } }", seriesId),
              "data.series.seasons[0].images[0].imageType");

      assertThat(imageType).isEqualTo("POSTER");
    }
  }

  @Nested
  @DisplayName("Episode Images")
  class EpisodeImages {

    @Test
    @DisplayName("Should load images when resolving episode images")
    void shouldLoadImagesWhenResolvingEpisodeImages() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Breaking Bad").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).build();
      season.setId(seasonId);

      var episodeId = UUID.randomUUID();
      var episode = Episode.builder().title("Pilot").episodeNumber(1).build();
      episode.setId(episodeId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
      when(seriesService.findEpisodes(seasonId)).thenReturn(List.of(episode));

      var image =
          buildImage(
              episodeId, ImageEntityType.EPISODE, ImageType.STILL, ImageSize.SMALL, 300, 169);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.EPISODE), any()))
          .thenReturn(List.of(image));

      String imageType =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { images { imageType } } } } }",
                  seriesId),
              "data.series.seasons[0].episodes[0].images[0].imageType");

      assertThat(imageType).isEqualTo("STILL");
    }
  }

  private Movie setupMovie() {
    var movieId = UUID.randomUUID();
    var movie = Movie.builder().title("Inception").build();
    movie.setId(movieId);
    when(movieService.findById(movieId)).thenReturn(Optional.of(movie));
    return movie;
  }

  private Image buildImage(
      UUID entityId,
      ImageEntityType entityType,
      ImageType imageType,
      ImageSize variant,
      int width,
      int height) {
    var image =
        Image.builder()
            .entityId(entityId)
            .entityType(entityType)
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
