package com.streamarr.server.graphql.dataloaders;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.services.watchprogress.WatchStatusService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Status DataLoader Tests")
class WatchStatusDataLoaderTest {

  private FakeSessionProgressRepository sessionProgressRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private WatchStatusDataLoader dataLoader;

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @BeforeEach
  void setUp() {
    sessionProgressRepository = new FakeSessionProgressRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    episodeRepository = new FakeEpisodeRepository();
    seasonRepository = new FakeSeasonRepository();
    var service =
        new WatchStatusService(
            sessionProgressRepository,
            new FakeWatchHistoryRepository(),
            mediaFileRepository,
            episodeRepository,
            seasonRepository,
            new CapturingEventPublisher());
    dataLoader = new WatchStatusDataLoader(service);
  }

  @Test
  @DisplayName("Should return in progress for direct media when files have progress")
  void shouldReturnInProgressForDirectMediaWhenFilesHaveProgress() throws Exception {
    var movie = Movie.builder().build();
    movie.setId(UUID.randomUUID());
    var mf = mediaFileRepository.save(createMediaFile(movie.getId()));
    sessionProgressRepository.save(buildProgressWithPosition(mf.getId(), 300));

    var key = new WatchStatusLoaderKey(movie.getId(), WatchStatusEntityType.DIRECT_MEDIA);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result).containsEntry(key, WatchStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("Should return unwatched when entity has no media files")
  void shouldReturnUnwatchedWhenEntityHasNoMediaFiles() throws Exception {
    var key = new WatchStatusLoaderKey(UUID.randomUUID(), WatchStatusEntityType.DIRECT_MEDIA);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result).containsEntry(key, WatchStatus.UNWATCHED);
  }

  @Test
  @DisplayName("Should return unwatched when media files exist but no progress")
  void shouldReturnUnwatchedWhenMediaFilesExistButNoProgress() throws Exception {
    var movie = Movie.builder().build();
    movie.setId(UUID.randomUUID());
    mediaFileRepository.save(createMediaFile(movie.getId()));

    var key = new WatchStatusLoaderKey(movie.getId(), WatchStatusEntityType.DIRECT_MEDIA);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result).containsEntry(key, WatchStatus.UNWATCHED);
  }

  @Test
  @DisplayName("Should batch mixed entity types in single load when keys span multiple types")
  void shouldBatchMixedEntityTypesInSingleLoadWhenKeysSpanMultipleTypes() throws Exception {
    var movie = Movie.builder().build();
    movie.setId(UUID.randomUUID());
    var movieMf = mediaFileRepository.save(createMediaFile(movie.getId()));
    sessionProgressRepository.save(buildProgressWithPosition(movieMf.getId(), 300));

    var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
    var ep = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
    mediaFileRepository.save(createMediaFile(ep.getId()));

    var movieKey = new WatchStatusLoaderKey(movie.getId(), WatchStatusEntityType.DIRECT_MEDIA);
    var seasonKey = new WatchStatusLoaderKey(season.getId(), WatchStatusEntityType.SEASON);

    var result = dataLoader.load(Set.of(movieKey, seasonKey)).toCompletableFuture().get();

    assertThat(result)
        .hasSize(2)
        .containsEntry(movieKey, WatchStatus.IN_PROGRESS)
        .containsEntry(seasonKey, WatchStatus.UNWATCHED);
  }

  @Test
  @DisplayName("Should return in progress when season has partial episode progress")
  void shouldReturnInProgressWhenSeasonHasPartialEpisodeProgress() throws Exception {
    var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
    var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
    var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());
    var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
    mediaFileRepository.save(createMediaFile(ep2.getId()));
    sessionProgressRepository.save(buildProgressWithPosition(mf1.getId(), 300));

    var key = new WatchStatusLoaderKey(season.getId(), WatchStatusEntityType.SEASON);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result).containsEntry(key, WatchStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("Should return in progress for series when all episodes have progress")
  void shouldReturnInProgressForSeriesWhenAllEpisodesHaveProgress() throws Exception {
    var series = Series.builder().build();
    series.setId(UUID.randomUUID());
    var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
    var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
    var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
    var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());
    var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
    var mf2 = mediaFileRepository.save(createMediaFile(ep2.getId()));
    sessionProgressRepository.save(buildProgressWithPosition(mf1.getId(), 300));
    sessionProgressRepository.save(buildProgressWithPosition(mf2.getId(), 600));

    var key = new WatchStatusLoaderKey(series.getId(), WatchStatusEntityType.SERIES);
    var result = dataLoader.load(Set.of(key)).toCompletableFuture().get();

    assertThat(result).containsEntry(key, WatchStatus.IN_PROGRESS);
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

  private SessionProgress buildProgressWithPosition(UUID mediaFileId, int positionSeconds) {
    return SessionProgress.builder()
        .sessionId(UUID.randomUUID())
        .userId(USER_ID)
        .mediaFileId(mediaFileId)
        .positionSeconds(positionSeconds)
        .percentComplete(50.0)
        .durationSeconds(7200)
        .build();
  }
}
