package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.graphql.dataloaders.SessionProgressDataLoader;
import com.streamarr.server.graphql.dataloaders.WatchStatusDataLoader;
import com.streamarr.server.services.MovieService;
import com.streamarr.server.services.SeriesService;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      WatchProgressFieldResolver.class,
      SessionProgressDataLoader.class,
      WatchStatusDataLoader.class,
      MovieResolver.class,
      SeriesResolver.class,
      SeriesFieldResolver.class,
      SeasonFieldResolver.class,
      WatchProgressFieldResolverTest.TestConfig.class
    })
@DisplayName("Watch Progress Field Resolver Tests")
class WatchProgressFieldResolverTest {

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private DgsQueryExecutor dgsQueryExecutor;
  @Autowired private FakeSessionProgressRepository sessionProgressRepository;
  @Autowired private FakeMediaFileRepository mediaFileRepository;
  @Autowired private FakeEpisodeRepository episodeRepository;
  @Autowired private FakeSeasonRepository seasonRepository;

  @MockitoBean private MovieService movieService;
  @MockitoBean private SeriesService seriesService;

  @TestConfiguration
  static class TestConfig {

    @Bean
    FakeSessionProgressRepository fakeSessionProgressRepository() {
      return new FakeSessionProgressRepository();
    }

    @Bean
    FakeMediaFileRepository fakeMediaFileRepository() {
      return new FakeMediaFileRepository();
    }

    @Bean
    FakeEpisodeRepository fakeEpisodeRepository() {
      return new FakeEpisodeRepository();
    }

    @Bean
    FakeSeasonRepository fakeSeasonRepository() {
      return new FakeSeasonRepository();
    }

    @Bean
    FakeWatchHistoryRepository fakeWatchHistoryRepository() {
      return new FakeWatchHistoryRepository();
    }

    @Bean
    CapturingEventPublisher capturingEventPublisher() {
      return new CapturingEventPublisher();
    }

    @Bean
    WatchStatusService watchStatusService(
        FakeSessionProgressRepository sessionProgressRepository,
        FakeWatchHistoryRepository watchHistoryRepository,
        FakeMediaFileRepository mediaFileRepository,
        FakeEpisodeRepository episodeRepository,
        FakeSeasonRepository seasonRepository,
        CapturingEventPublisher eventPublisher) {
      return new WatchStatusService(
          sessionProgressRepository,
          watchHistoryRepository,
          mediaFileRepository,
          episodeRepository,
          seasonRepository,
          eventPublisher);
    }
  }

  @BeforeEach
  void setUp() {
    sessionProgressRepository.deleteAll();
    mediaFileRepository.deleteAll();
    episodeRepository.deleteAll();
    seasonRepository.deleteAll();
  }

  @Nested
  @DisplayName("Movie Watch Progress")
  class MovieWatchProgress {

    @Test
    @DisplayName("Should return watch progress when movie has media files")
    void shouldReturnWatchProgressWhenMovieHasMediaFiles() {
      var movie = setupMovie();
      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .userId(USER_ID)
              .mediaFileId(mediaFile.getId())
              .positionSeconds(1800)
              .percentComplete(50.0)
              .durationSeconds(3600)
              .build());

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ movie(id: \"%s\") { watchProgress { positionSeconds percentComplete durationSeconds } } }",
                  movie.getId()));

      assertThat(context.<Integer>read("data.movie.watchProgress.positionSeconds")).isEqualTo(1800);
      assertThat(context.<Double>read("data.movie.watchProgress.percentComplete")).isEqualTo(50.0);
      assertThat(context.<Integer>read("data.movie.watchProgress.durationSeconds")).isEqualTo(3600);
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
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);

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
    @DisplayName("Should return in progress when movie has partial progress")
    void shouldReturnInProgressWhenMovieHasPartialProgress() {
      var movie = setupMovie();

      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(movie.getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .userId(USER_ID)
              .mediaFileId(mediaFile.getId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build());

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
      mediaFile.setMediaId(episodeId);
      mediaFileRepository.save(mediaFile);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
      when(seriesService.findEpisodes(seasonId)).thenReturn(List.of(episode));

      sessionProgressRepository.save(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .userId(USER_ID)
              .mediaFileId(mediaFile.getId())
              .positionSeconds(600)
              .percentComplete(25.0)
              .durationSeconds(2400)
              .build());

      var context =
          dgsQueryExecutor.executeAndGetDocumentContext(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { watchProgress { positionSeconds percentComplete durationSeconds } } } } }",
                  seriesId));

      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].episodes[0].watchProgress.positionSeconds"))
          .isEqualTo(600);
      assertThat(
              context.<Double>read(
                  "data.series.seasons[0].episodes[0].watchProgress.percentComplete"))
          .isEqualTo(25.0);
      assertThat(
              context.<Integer>read(
                  "data.series.seasons[0].episodes[0].watchProgress.durationSeconds"))
          .isEqualTo(2400);
    }

    @Test
    @DisplayName("Should return in progress when episode has active progress")
    void shouldReturnInProgressWhenEpisodeHasActiveProgress() {
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

      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(episodeId);
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .userId(USER_ID)
              .mediaFileId(mediaFile.getId())
              .positionSeconds(1200)
              .percentComplete(50.0)
              .durationSeconds(2400)
              .build());

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { watchStatus } } } }", seriesId),
              "data.series.seasons[0].episodes[0].watchStatus");

      assertThat(status).isEqualTo("IN_PROGRESS");
    }
  }

  @Nested
  @DisplayName("Season Watch Status")
  class SeasonWatchStatusTests {

    @Test
    @DisplayName("Should return in progress when season has partial progress")
    void shouldReturnInProgressWhenSeasonHasPartialProgress() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).build();
      season.setId(seasonId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));

      var episode = Episode.builder().episodeNumber(1).season(season).build();
      episode.setId(UUID.randomUUID());
      episodeRepository.save(episode);

      var mediaFile = buildMediaFile();
      mediaFile.setMediaId(episode.getId());
      mediaFileRepository.save(mediaFile);

      sessionProgressRepository.save(
          SessionProgress.builder()
              .sessionId(UUID.randomUUID())
              .userId(USER_ID)
              .mediaFileId(mediaFile.getId())
              .positionSeconds(300)
              .percentComplete(25.0)
              .durationSeconds(1200)
              .build());

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
    @DisplayName("Should return unwatched when series has no progress")
    void shouldReturnUnwatchedWhenSeriesHasNoProgress() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));

      String status =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { watchStatus } }", seriesId),
              "data.series.watchStatus");

      assertThat(status).isEqualTo("UNWATCHED");
    }
  }

  @Nested
  @DisplayName("Season-Series Navigation")
  class SeasonSeriesNavigationTests {

    @Test
    @DisplayName("Should resolve series from season")
    void shouldResolveSeriesFromSeason() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Parent Series").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).series(series).build();
      season.setId(seasonId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));

      String seriesTitle =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format("{ series(id: \"%s\") { seasons { series { title } } } }", seriesId),
              "data.series.seasons[0].series.title");

      assertThat(seriesTitle).isEqualTo("Parent Series");
    }

    @Test
    @DisplayName("Should resolve season from episode")
    void shouldResolveSeasonFromEpisode() {
      var seriesId = UUID.randomUUID();
      var series = Series.builder().title("Test Series").build();
      series.setId(seriesId);

      var seasonId = UUID.randomUUID();
      var season = Season.builder().title("Season 1").seasonNumber(1).series(series).build();
      season.setId(seasonId);

      var episodeId = UUID.randomUUID();
      var episode = Episode.builder().title("Pilot").episodeNumber(1).season(season).build();
      episode.setId(episodeId);

      when(seriesService.findById(seriesId)).thenReturn(Optional.of(series));
      when(seriesService.findSeasons(seriesId)).thenReturn(List.of(season));
      when(seriesService.findEpisodes(seasonId)).thenReturn(List.of(episode));
      when(seriesService.findSeason(seasonId)).thenReturn(season);

      Integer seasonNumber =
          dgsQueryExecutor.executeAndExtractJsonPath(
              String.format(
                  "{ series(id: \"%s\") { seasons { episodes { season { seasonNumber } } } } }",
                  seriesId),
              "data.series.seasons[0].episodes[0].season.seasonNumber");

      assertThat(seasonNumber).isEqualTo(1);
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
