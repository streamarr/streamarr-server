package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeWatchProgressRepository;
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

  private FakeWatchProgressRepository watchProgressRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private WatchStatusService service;

  private static final UUID USER_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    watchProgressRepository = new FakeWatchProgressRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    episodeRepository = new FakeEpisodeRepository();
    seasonRepository = new FakeSeasonRepository();
    service =
        new WatchStatusService(
            watchProgressRepository, mediaFileRepository, episodeRepository, seasonRepository);
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

  private WatchProgress buildProgress(UUID mediaFileId, int positionSeconds) {
    return WatchProgress.builder()
        .userId(USER_ID)
        .mediaFileId(mediaFileId)
        .positionSeconds(positionSeconds)
        .percentComplete(50.0)
        .durationSeconds(7200)
        .build();
  }

  private WatchProgress buildPlayedProgress(UUID mediaFileId) {
    return WatchProgress.builder()
        .userId(USER_ID)
        .mediaFileId(mediaFileId)
        .positionSeconds(0)
        .percentComplete(100.0)
        .durationSeconds(7200)
        .lastPlayedAt(Instant.now())
        .build();
  }

  @Nested
  @DisplayName("Get Progress")
  class GetProgress {

    @Test
    @DisplayName("Should return progress when it exists for user and media file")
    void shouldReturnProgressWhenItExistsForUserAndMediaFile() {
      var mediaFileId = UUID.randomUUID();
      watchProgressRepository.save(buildProgress(mediaFileId, 600));

      var result = service.getProgress(USER_ID, mediaFileId);

      assertThat(result).isPresent();
      assertThat(result.get().getPositionSeconds()).isEqualTo(600);
      assertThat(result.get().getPercentComplete()).isEqualTo(50.0);
      assertThat(result.get().getDurationSeconds()).isEqualTo(7200);
    }

    @Test
    @DisplayName("Should return empty when no progress exists")
    void shouldReturnEmptyWhenNoProgressExists() {
      var result = service.getProgress(USER_ID, UUID.randomUUID());

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Direct Media")
  class BatchWatchStatusForDirectMedia {

    @Test
    @DisplayName("Should return watched when all media files are played")
    void shouldReturnWatchedWhenAllMediaFilesArePlayed() {
      var movie = Movie.builder().build();
      movie.setId(UUID.randomUUID());

      var mf1 = mediaFileRepository.save(createMediaFile(movie.getId()));
      var mf2 = mediaFileRepository.save(createMediaFile(movie.getId()));

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));
      watchProgressRepository.save(buildPlayedProgress(mf2.getId()));

      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(movie.getId()));

      assertThat(result).containsEntry(movie.getId(), WatchStatus.WATCHED);
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

      watchProgressRepository.save(buildProgress(mf1.getId(), 300));

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

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));

      var result =
          service.getWatchStatusForDirectMedia(USER_ID, List.of(movie1.getId(), movie2.getId()));

      assertThat(result)
          .containsEntry(movie1.getId(), WatchStatus.WATCHED)
          .containsEntry(movie2.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should return empty map when no media files exist")
    void shouldReturnEmptyMapWhenNoMediaFilesExist() {
      var result = service.getWatchStatusForDirectMedia(USER_ID, List.of(UUID.randomUUID()));

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Seasons")
  class BatchWatchStatusForSeasons {

    @Test
    @DisplayName("Should return watched when all episodes are played")
    void shouldReturnWatchedWhenAllEpisodesArePlayed() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      var mf2 = mediaFileRepository.save(createMediaFile(ep2.getId()));

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));
      watchProgressRepository.save(buildPlayedProgress(mf2.getId()));

      var result = service.getWatchStatusForSeasons(USER_ID, List.of(season.getId()));

      assertThat(result).containsEntry(season.getId(), WatchStatus.WATCHED);
    }

    @Test
    @DisplayName("Should return in progress when some episodes have progress")
    void shouldReturnInProgressWhenSomeEpisodesHaveProgress() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      mediaFileRepository.save(createMediaFile(ep2.getId()));

      watchProgressRepository.save(buildProgress(mf1.getId(), 300));

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

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));

      var result = service.getWatchStatusForSeasons(USER_ID, List.of(s1.getId(), s2.getId()));

      assertThat(result)
          .containsEntry(s1.getId(), WatchStatus.WATCHED)
          .containsEntry(s2.getId(), WatchStatus.UNWATCHED);
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Series")
  class BatchWatchStatusForSeries {

    @Test
    @DisplayName("Should return watched when all episodes across seasons are played")
    void shouldReturnWatchedWhenAllEpisodesAcrossSeasonsArePlayed() {
      var series = Series.builder().build();
      series.setId(UUID.randomUUID());
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      var mf2 = mediaFileRepository.save(createMediaFile(ep2.getId()));

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));
      watchProgressRepository.save(buildPlayedProgress(mf2.getId()));

      var result = service.getWatchStatusForSeries(USER_ID, List.of(series.getId()));

      assertThat(result).containsEntry(series.getId(), WatchStatus.WATCHED);
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

      watchProgressRepository.save(buildProgress(mf1.getId(), 300));

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

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));

      var result =
          service.getWatchStatusForSeries(USER_ID, List.of(series1.getId(), series2.getId()));

      assertThat(result)
          .containsEntry(series1.getId(), WatchStatus.WATCHED)
          .containsEntry(series2.getId(), WatchStatus.UNWATCHED);
    }
  }
}
