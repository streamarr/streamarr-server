package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildEpisode;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeason;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.AmbientColors;
import com.streamarr.server.domain.media.Image;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageSize;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.graphql.dataloaders.ImageDataLoader;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.media.ImageRepository;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.support.security.WithProfileContext;
import java.util.List;
import java.util.Map;
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
@WithProfileContext
@SpringBootTest(
    classes = {
      ImageFieldResolver.class,
      AmbientColorsFieldResolver.class,
      ImageDataLoader.class,
      MovieResolver.class,
      SeriesResolver.class,
      SeriesFieldResolver.class,
      SeasonFieldResolver.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class,
    })
@DisplayName("Image Field Resolver Tests")
class ImageFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private UserAccountRepository userAccountRepository;
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
      var image = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
          .thenReturn(List.of(image));

      String imageType =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ movie(id: \"%s\") { images { imageType } } }", movie.getId()),
              "data.movie.images[0].imageType");

      assertThat(imageType).isEqualTo("POSTER");
    }

    @Test
    @DisplayName("Should serialize ambient colors when resolving movie images")
    void shouldSerializeAmbientColorsWhenResolvingMovieImages() {
      var movie = setupMovie();
      var ambientColors =
          AmbientColors.builder()
              .topLeft("#101010")
              .topRight("#202020")
              .bottomRight("#303030")
              .bottomLeft("#404040")
              .primary("#00a0a0")
              .build();
      var image = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER);
      image.setAmbientColors(Optional.of(ambientColors));
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
          .thenReturn(List.of(image));

      Map<String, String> result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              """
              {
                movie(id: "%s") {
                  images {
                    ambientColors {
                      topLeft
                      topRight
                      bottomRight
                      bottomLeft
                      primary
                    }
                  }
                }
              }
              """
                  .formatted(movie.getId()),
              "data.movie.images[0].ambientColors");

      assertThat(result)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of(
                  "topLeft", "#101010",
                  "topRight", "#202020",
                  "bottomRight", "#303030",
                  "bottomLeft", "#404040",
                  "primary", "#00a0a0"));
    }

    @Test
    @DisplayName("Should derive the ambient theme when resolving ambient colors")
    void shouldDeriveAmbientThemeWhenResolvingAmbientColors() {
      var movie = setupMovie();
      var ambientColors =
          AmbientColors.builder()
              .topLeft("#0d322c")
              .topRight("#0d322c")
              .bottomRight("#0d322c")
              .bottomLeft("#0d322c")
              .primary("#6fe0bf")
              .darkMuted("#0e3b34")
              .darkVibrant("#1f6b5a")
              .build();
      var image = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER);
      image.setAmbientColors(Optional.of(ambientColors));
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
          .thenReturn(List.of(image));

      Map<String, String> theme =
          dgsQueryExecutor.executeAndExtractJsonPath(
              """
              {
                movie(id: "%s") {
                  images {
                    ambientColors {
                      theme {
                        base
                        panel
                        selected
                        accent
                        onAccent
                        textPrimary
                        textSecondary
                      }
                    }
                  }
                }
              }
              """
                  .formatted(movie.getId()),
              "data.movie.images[0].ambientColors.theme");

      assertThat(theme)
          .containsEntry("base", "#0e3b34")
          .containsEntry("selected", "#1f6b5a")
          .containsEntry("accent", "#6fe0bf")
          .containsKeys("panel", "onAccent", "textPrimary", "textSecondary");
    }

    @Test
    @DisplayName("Should serialize null ambient colors when small image has none")
    void shouldSerializeNullAmbientColorsWhenSmallImageHasNone() {
      var movie = setupMovie();
      var image = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.MOVIE), any()))
          .thenReturn(List.of(image));

      Object result =
          dgsQueryExecutor.executeAndExtractJsonPath(
              """
              {
                movie(id: "%s") {
                  images {
                    ambientColors {
                      primary
                    }
                  }
                }
              }
              """
                  .formatted(movie.getId()),
              "data.movie.images[0].ambientColors");

      assertThat(result).isNull();
    }

    @Test
    @DisplayName("Should filter by image type when type argument provided")
    void shouldFilterByImageTypeWhenTypeArgumentProvided() {
      var movie = setupMovie();
      var posterImage = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER);
      var backdropImage = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.BACKDROP);
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
      var posterImage = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.POSTER);
      var backdropImage = buildImage(movie.getId(), ImageEntityType.MOVIE, ImageType.BACKDROP);
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
      var series = buildSeries("Breaking Bad");
      when(seriesService.findById(series.getId())).thenReturn(Optional.of(series));

      var posterImage = buildImage(series.getId(), ImageEntityType.SERIES, ImageType.POSTER);
      var backdropImage = buildImage(series.getId(), ImageEntityType.SERIES, ImageType.BACKDROP);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.SERIES), any()))
          .thenReturn(List.of(posterImage, backdropImage));

      List<String> imageTypes =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { images { imageType } } }", series.getId()),
              "data.series.images[*].imageType");

      assertThat(imageTypes).containsExactlyInAnyOrder("POSTER", "BACKDROP");
    }
  }

  @Nested
  @DisplayName("Season Images")
  class SeasonImages {

    @Test
    @DisplayName("Should load images when resolving season images")
    void shouldLoadImagesWhenResolvingSeasonImages() {
      var series = buildSeries("Breaking Bad");

      var season = buildSeason("Season 1", 1);

      when(seriesService.findById(series.getId())).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(series.getId())).thenReturn(List.of(season));

      var posterImage = buildImage(season.getId(), ImageEntityType.SEASON, ImageType.POSTER);
      var backdropImage = buildImage(season.getId(), ImageEntityType.SEASON, ImageType.BACKDROP);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.SEASON), any()))
          .thenReturn(List.of(posterImage, backdropImage));

      List<String> imageTypes =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { images { imageType } } } }", series.getId()),
              "data.series.seasons[0].images[*].imageType");

      assertThat(imageTypes).containsExactlyInAnyOrder("POSTER", "BACKDROP");
    }
  }

  @Nested
  @DisplayName("Episode Images")
  class EpisodeImages {

    @Test
    @DisplayName("Should load images when resolving episode images")
    void shouldLoadImagesWhenResolvingEpisodeImages() {
      var series = buildSeries("Breaking Bad");

      var season = buildSeason("Season 1", 1);

      var episode = buildEpisode("Pilot", 1);

      when(seriesService.findById(series.getId())).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(series.getId())).thenReturn(List.of(season));
      when(seriesService.findEpisodes(season.getId())).thenReturn(List.of(episode));

      var stillImage = buildImage(episode.getId(), ImageEntityType.EPISODE, ImageType.STILL);
      var backdropImage = buildImage(episode.getId(), ImageEntityType.EPISODE, ImageType.BACKDROP);
      when(imageRepository.findByEntityTypeAndEntityIdIn(eq(ImageEntityType.EPISODE), any()))
          .thenReturn(List.of(stillImage, backdropImage));

      List<String> imageTypes =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { images { imageType } } } } }",
                  series.getId()),
              "data.series.seasons[0].episodes[0].images[*].imageType");

      assertThat(imageTypes).containsExactlyInAnyOrder("STILL", "BACKDROP");
    }
  }

  private Movie setupMovie() {
    var movie = buildMovie("Inception");
    when(movieService.findById(movie.getId())).thenReturn(Optional.of(movie));
    return movie;
  }

  private Image buildImage(UUID entityId, ImageEntityType entityType, ImageType imageType) {
    var image =
        Image.builder()
            .entityId(entityId)
            .entityType(entityType)
            .imageType(imageType)
            .variant(ImageSize.SMALL)
            .width(185)
            .height(278)
            .path("test/path.jpg")
            .build();
    image.setId(UUID.randomUUID());
    return image;
  }
}
