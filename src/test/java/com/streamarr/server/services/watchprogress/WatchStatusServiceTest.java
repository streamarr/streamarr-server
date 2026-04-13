package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Status Service Tests")
class WatchStatusServiceTest {

  private FakeSessionProgressRepository sessionProgressRepository;
  private FakeWatchHistoryRepository watchHistoryRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private CapturingEventPublisher eventPublisher;
  private WatchStatusService service;

  private static final UUID USER_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    sessionProgressRepository = new FakeSessionProgressRepository();
    watchHistoryRepository = new FakeWatchHistoryRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    episodeRepository = new FakeEpisodeRepository();
    seasonRepository = new FakeSeasonRepository();
    eventPublisher = new CapturingEventPublisher();
    service =
        new WatchStatusService(
            sessionProgressRepository,
            watchHistoryRepository,
            mediaFileRepository,
            episodeRepository,
            seasonRepository,
            eventPublisher);
  }

  private MediaFile createMediaFile(UUID mediaId) {
    return MediaFile.builder()
        .mediaId(mediaId)
        .filename("file-" + UUID.randomUUID() + ".mkv")
        .filepathUri("/media/" + UUID.randomUUID() + ".mkv")
        .size(1_000_000)
        .status(MediaFileStatus.MATCHED)
        .build();
  }

  private SessionProgress buildProgress(UUID mediaFileId, int positionSeconds) {
    return SessionProgress.builder()
        .sessionId(UUID.randomUUID())
        .userId(USER_ID)
        .mediaFileId(mediaFileId)
        .positionSeconds(positionSeconds)
        .percentComplete(50.0)
        .durationSeconds(7200)
        .build();
  }

  @Nested
  @DisplayName("Watch History")
  class WatchHistoryTests {

    @Test
    @DisplayName("Should create watch history entry when marking watched explicitly")
    void shouldCreateWatchHistoryEntryWhenMarkingWatchedExplicitly() {
      var movieId = UUID.randomUUID();

      service.markWatched(USER_ID, movieId, CollectableScope.DIRECT_MEDIA, Instant.now(), 7200);

      var history =
          watchHistoryRepository.findFirstByUserIdAndCollectableIdOrderByWatchedAtDesc(
              USER_ID, movieId);
      assertThat(history).isPresent();
      assertThat(history.get().getDurationSeconds()).isEqualTo(7200);
      assertThat(history.get().getDismissedAt()).isNull();
    }

    @Test
    @DisplayName("Should mark all episodes when marking season as watched")
    void shouldMarkAllEpisodesWhenMarkingSeasonAsWatched() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      service.markWatched(USER_ID, season.getId(), CollectableScope.SEASON, Instant.now(), 3600);

      assertThat(watchHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should derive watched status from watch history")
    void shouldDeriveWatchedStatusFromWatchHistory() {
      var movie = Movie.builder().build();
      movie.setId(UUID.randomUUID());
      mediaFileRepository.save(createMediaFile(movie.getId()));

      service.markWatched(
          USER_ID, movie.getId(), CollectableScope.DIRECT_MEDIA, Instant.now(), 7200);

      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(movie.getId()));
      assertThat(result).containsEntry(movie.getId(), WatchStatus.WATCHED);
    }

    @Test
    @DisplayName("Should derive unwatched when latest history entry is dismissed")
    void shouldDeriveUnwatchedWhenLatestHistoryEntryIsDismissed() {
      var movie = Movie.builder().build();
      movie.setId(UUID.randomUUID());
      mediaFileRepository.save(createMediaFile(movie.getId()));

      service.markWatched(
          USER_ID, movie.getId(), CollectableScope.DIRECT_MEDIA, Instant.now(), 7200);
      service.markUnwatched(USER_ID, movie.getId());

      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(movie.getId()));
      assertThat(result).containsEntry(movie.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should keep watched status during re-watch with active session progress")
    void shouldKeepWatchedStatusDuringReWatchWithActiveSessionProgress() {
      var movie = Movie.builder().build();
      movie.setId(UUID.randomUUID());
      var mf = mediaFileRepository.save(createMediaFile(movie.getId()));

      service.markWatched(
          USER_ID, movie.getId(), CollectableScope.DIRECT_MEDIA, Instant.now(), 7200);

      // Simulate active re-watch session
      sessionProgressRepository.save(buildProgress(mf.getId(), 1800));

      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(movie.getId()));
      assertThat(result).containsEntry(movie.getId(), WatchStatus.WATCHED);
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Direct Media")
  class BatchWatchStatusForDirectMedia {

    @Test
    @DisplayName("Should return in progress when media files have progress")
    void shouldReturnInProgressWhenMediaFilesHaveProgress() {
      var movie = Movie.builder().build();
      movie.setId(UUID.randomUUID());

      var mf1 = mediaFileRepository.save(createMediaFile(movie.getId()));
      var mf2 = mediaFileRepository.save(createMediaFile(movie.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));
      sessionProgressRepository.save(buildProgress(mf2.getId(), 600));

      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(movie.getId()));

      assertThat(result).containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return unwatched when no progress exists")
    void shouldReturnUnwatchedWhenNoProgressExists() {
      var movie = Movie.builder().build();
      movie.setId(UUID.randomUUID());

      mediaFileRepository.save(createMediaFile(movie.getId()));

      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(movie.getId()));

      assertThat(result).containsEntry(movie.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should return in progress when some files have progress")
    void shouldReturnInProgressWhenSomeFilesHaveProgress() {
      var movie = Movie.builder().build();
      movie.setId(UUID.randomUUID());

      var mf1 = mediaFileRepository.save(createMediaFile(movie.getId()));
      mediaFileRepository.save(createMediaFile(movie.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));

      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(movie.getId()));

      assertThat(result).hasSize(1).containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should batch multiple collectables in single call when given multiple IDs")
    void shouldBatchMultipleCollectablesInSingleCallWhenGivenMultipleIds() {
      var movie1 = Movie.builder().build();
      movie1.setId(UUID.randomUUID());
      var movie2 = Movie.builder().build();
      movie2.setId(UUID.randomUUID());

      var mf1 = mediaFileRepository.save(createMediaFile(movie1.getId()));
      mediaFileRepository.save(createMediaFile(movie2.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));

      var result =
          service.getWatchStatusForDirectMedia(USER_ID, List.of(movie1.getId(), movie2.getId()));

      assertThat(result)
          .containsEntry(movie1.getId(), WatchStatus.IN_PROGRESS)
          .containsEntry(movie2.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should return unwatched when no media files exist")
    void shouldReturnUnwatchedWhenNoMediaFilesExist() {
      var unknownId = UUID.randomUUID();
      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(unknownId));

      assertThat(result).containsEntry(unknownId, WatchStatus.UNWATCHED);
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Seasons")
  class BatchWatchStatusForSeasons {

    @Test
    @DisplayName("Should return in progress when all episodes have progress")
    void shouldReturnInProgressWhenAllEpisodesHaveProgress() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      var mf2 = mediaFileRepository.save(createMediaFile(ep2.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));
      sessionProgressRepository.save(buildProgress(mf2.getId(), 600));

      var result = service.getWatchStatusForSeasons(USER_ID, List.of(season.getId()));

      assertThat(result).containsEntry(season.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return in progress when some episodes have progress")
    void shouldReturnInProgressWhenSomeEpisodesHaveProgress() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      mediaFileRepository.save(createMediaFile(ep2.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));

      var result = service.getWatchStatusForSeasons(USER_ID, List.of(season.getId()));

      assertThat(result).containsEntry(season.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return empty map when no episodes exist")
    void shouldReturnEmptyMapWhenNoEpisodesExist() {
      var result = service.getWatchStatusForSeasons(USER_ID, List.of(UUID.randomUUID()));

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should batch multiple seasons in single call when given multiple season IDs")
    void shouldBatchMultipleSeasonsInSingleCallWhenGivenMultipleSeasonIds() {
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      mediaFileRepository.save(createMediaFile(ep2.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));

      var result = service.getWatchStatusForSeasons(USER_ID, List.of(s1.getId(), s2.getId()));

      assertThat(result)
          .containsEntry(s1.getId(), WatchStatus.IN_PROGRESS)
          .containsEntry(s2.getId(), WatchStatus.UNWATCHED);
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Series")
  class BatchWatchStatusForSeries {

    @Test
    @DisplayName("Should return in progress when all episodes across seasons have progress")
    void shouldReturnInProgressWhenAllEpisodesAcrossSeasonsHaveProgress() {
      var series = Series.builder().build();
      series.setId(UUID.randomUUID());
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      var mf2 = mediaFileRepository.save(createMediaFile(ep2.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));
      sessionProgressRepository.save(buildProgress(mf2.getId(), 600));

      var result = service.getWatchStatusForSeries(USER_ID, List.of(series.getId()));

      assertThat(result).containsEntry(series.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return in progress when some episodes have progress")
    void shouldReturnInProgressWhenSomeEpisodesHaveProgress() {
      var series = Series.builder().build();
      series.setId(UUID.randomUUID());
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      mediaFileRepository.save(createMediaFile(ep2.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));

      var result = service.getWatchStatusForSeries(USER_ID, List.of(series.getId()));

      assertThat(result).hasSize(1).containsEntry(series.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return empty map when no seasons exist")
    void shouldReturnEmptyMapWhenNoSeasonsExist() {
      var result = service.getWatchStatusForSeries(USER_ID, List.of(UUID.randomUUID()));

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should batch multiple series in single call when given multiple series IDs")
    void shouldBatchMultipleSeriesInSingleCallWhenGivenMultipleSeriesIds() {
      var series1 = Series.builder().build();
      series1.setId(UUID.randomUUID());
      var series2 = Series.builder().build();
      series2.setId(UUID.randomUUID());
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series1).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(1).series(series2).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      mediaFileRepository.save(createMediaFile(ep2.getId()));

      sessionProgressRepository.save(buildProgress(mf1.getId(), 300));

      var result =
          service.getWatchStatusForSeries(USER_ID, List.of(series1.getId(), series2.getId()));

      assertThat(result)
          .containsEntry(series1.getId(), WatchStatus.IN_PROGRESS)
          .containsEntry(series2.getId(), WatchStatus.UNWATCHED);
    }
  }
}
