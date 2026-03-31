package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.graphql.dataloaders.WatchProgressDataLoader;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.watchprogress.WatchProgressService;
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
@SpringBootTest(
    classes = {
      WatchProgressFieldResolver.class,
      WatchProgressDataLoader.class,
      MovieResolver.class,
      SeriesResolver.class,
      SeriesFieldResolver.class,
      SeasonFieldResolver.class
    })
@DisplayName("Watch Progress Field Resolver Tests")
class WatchProgressFieldResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private MovieService movieService;
  @MockitoBean private SeriesService seriesService;
  @MockitoBean private WatchProgressService watchProgressService;

  @Nested
  @DisplayName("Movie Watch Progress")
  class MovieWatchProgress {

    @Test
    @DisplayName("Should return watch progress when movie has media files")
    void shouldReturnWatchProgressWhenMovieHasMediaFiles() {
      var movie = setupMovie();
      var mediaFile = buildMediaFile();
      movie.getFiles().add(mediaFile);

      when(movieService.findMediaFiles(movie.getId())).thenReturn(List.of(mediaFile));

      var progress =
          WatchProgress.builder()
              .userId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
              .mediaFileId(mediaFile.getId())
              .positionSeconds(1800)
              .percentComplete(50.0)
              .durationSeconds(3600)
              .build();
      when(watchProgressService.getProgressForMediaFiles(any(), any()))
          .thenReturn(Map.of(mediaFile.getId(), progress));

      Integer positionSeconds =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { watchProgress { positionSeconds percentComplete durationSeconds } } }",
                  movie.getId()),
              "data.movie.watchProgress.positionSeconds");

      assertThat(positionSeconds).isEqualTo(1800);
    }

    @Test
    @DisplayName("Should return null watch progress when movie has no files")
    void shouldReturnNullWatchProgressWhenMovieHasNoFiles() {
      var movie = setupMovie();

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { watchProgress { positionSeconds } } }", movie.getId()),
              "data.movie.watchProgress");

      assertThat(watchProgress).isNull();
    }

    @Test
    @DisplayName("Should return null watch progress when no progress exists")
    void shouldReturnNullWatchProgressWhenNoProgressExists() {
      var movie = setupMovie();
      var mediaFile = buildMediaFile();
      movie.getFiles().add(mediaFile);

      when(movieService.findMediaFiles(movie.getId())).thenReturn(List.of(mediaFile));
      when(watchProgressService.getProgressForMediaFiles(any(), any())).thenReturn(Map.of());

      Object watchProgress =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ movie(id: \"%s\") { watchProgress { positionSeconds } } }", movie.getId()),
              "data.movie.watchProgress");

      assertThat(watchProgress).isNull();
    }
  }

  @Nested
  @DisplayName("Movie Watch Status")
  class MovieWatchStatusTests {

    @Test
    @DisplayName("Should return watch status when movie queried")
    void shouldReturnWatchStatusWhenMovieQueried() {
      var movie = setupMovie();
      when(watchProgressService.getWatchStatusForCollectable(any(), eq(movie.getId())))
          .thenReturn(WatchStatus.IN_PROGRESS);

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ movie(id: \"%s\") { watchStatus } }", movie.getId()),
              "data.movie.watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
    }
  }

  @Nested
  @DisplayName("Episode Watch Progress and Status")
  class EpisodeWatchProgressAndStatus {

    @Test
    @DisplayName("Should return watch progress when episode has media files")
    void shouldReturnWatchProgressWhenEpisodeHasMediaFiles() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).build();
      season.setId(seasonId);

      var episodeId = UUID.randomUUID();
      var episode = Episode.builder().title("Pilot").episodeNumber(1).build();
      episode.setId(episodeId);

      var mediaFile = buildMediaFile();
      episode.getFiles().add(mediaFile);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
      when(seriesService.findEpisodes(seasonId)).thenReturn(List.of(episode));
      when(seriesService.findMediaFiles(episodeId)).thenReturn(List.of(mediaFile));

      var progress =
          WatchProgress.builder()
              .userId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
              .mediaFileId(mediaFile.getId())
              .positionSeconds(600)
              .percentComplete(25.0)
              .durationSeconds(2400)
              .build();
      when(watchProgressService.getProgressForMediaFiles(any(), any()))
          .thenReturn(Map.of(mediaFile.getId(), progress));

      Integer positionSeconds =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { watchProgress { positionSeconds } } } } }",
                  seriesId),
              "data.series.seasons[0].episodes[0].watchProgress.positionSeconds");

      assertThat(positionSeconds).isEqualTo(600);
    }

    @Test
    @DisplayName("Should return watch status when episode queried")
    void shouldReturnWatchStatusWhenEpisodeQueried() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
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
      when(watchProgressService.getWatchStatusForCollectable(any(), eq(episodeId)))
          .thenReturn(WatchStatus.WATCHED);

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { watchStatus } } } }", seriesId),
              "data.series.seasons[0].episodes[0].watchStatus");

      assertThat(status).isEqualTo("WATCHED");
    }
  }

  @Nested
  @DisplayName("Season Watch Status")
  class SeasonWatchStatusTests {

    @Test
    @DisplayName("Should return watch status when season queried")
    void shouldReturnWatchStatusWhenSeasonQueried() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).build();
      season.setId(seasonId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
      when(watchProgressService.getWatchStatusForCollectable(any(), eq(seasonId)))
          .thenReturn(WatchStatus.IN_PROGRESS);

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { seasons { watchStatus } } }", seriesId),
              "data.series.seasons[0].watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
    }
  }

  @Nested
  @DisplayName("Series Watch Status")
  class SeriesWatchStatusTests {

    @Test
    @DisplayName("Should return watch status when series queried")
    void shouldReturnWatchStatusWhenSeriesQueried() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(watchProgressService.getWatchStatusForCollectable(any(), eq(seriesId)))
          .thenReturn(WatchStatus.UNWATCHED);

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { watchStatus } }", seriesId),
              "data.series.watchStatus");

      assertThat(status).isEqualTo("UNWATCHED");
    }
  }

  private Movie setupMovie() {
    var movieId = UUID.randomUUID();
    var movie = Movie.builder().title("Test Movie").build();
    movie.setId(movieId);
    when(movieService.findById(movieId)).thenReturn(Optional.of(movie));
    return movie;
  }

  private MediaFile buildMediaFile() {
    var mediaFile =
        MediaFile.builder()
            .filename("test.mkv")
            .filepathUri("/media/test.mkv")
            .size(1_000_000)
            .build();
    mediaFile.setId(UUID.randomUUID());
    return mediaFile;
  }
}
