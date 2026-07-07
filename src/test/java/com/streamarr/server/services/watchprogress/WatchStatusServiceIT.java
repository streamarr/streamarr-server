package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.repositories.media.EpisodeRepository;
import com.streamarr.server.repositories.media.SeasonRepository;
import com.streamarr.server.repositories.media.SeriesRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.repositories.streaming.WatchHistoryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Watch Status Service Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WatchStatusServiceIT extends AbstractIntegrationTest {

  @Autowired private LibraryRepository libraryRepository;
  @Autowired private SeriesRepository seriesRepository;
  @Autowired private SeasonRepository seasonRepository;
  @Autowired private EpisodeRepository episodeRepository;
  @Autowired private WatchHistoryRepository watchHistoryRepository;
  @Autowired private SessionProgressRepository sessionProgressRepository;
  @Autowired private WatchStatusService watchStatusService;

  private Library library;

  @BeforeAll
  void setup() {
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
  }

  @Test
  @DisplayName("Should write history for every episode when marking series watched by id")
  void shouldWriteHistoryForEveryEpisodeWhenMarkingSeriesWatchedById() {
    var userId = UUID.randomUUID();
    var series = createSeries("Fully Watched Series");
    var seasonOne = createSeason(series, 1);
    var seasonTwo = createSeason(series, 2);
    var episodeIds =
        List.of(
            createEpisode(seasonOne, 1).getId(),
            createEpisode(seasonOne, 2).getId(),
            createEpisode(seasonTwo, 1).getId(),
            createEpisode(seasonTwo, 2).getId());

    watchStatusService.markWatched(userId, series.getId());

    var history = watchHistoryRepository.findByUserIdAndCollectableIdIn(userId, episodeIds);
    assertThat(history)
        .extracting(WatchHistory::getCollectableId)
        .containsExactlyInAnyOrderElementsOf(episodeIds);
    assertThat(watchStatusService.getWatchStatusForSeries(userId, List.of(series.getId())))
        .containsEntry(series.getId(), WatchStatus.WATCHED);
  }

  @Test
  @DisplayName("Should insert history once when marking series watched twice at same instant")
  void shouldInsertHistoryOnceWhenMarkingSeriesWatchedTwiceAtSameInstant() {
    var userId = UUID.randomUUID();
    var series = createSeries("Idempotent Series");
    var season = createSeason(series, 1);
    var episodeIds = List.of(createEpisode(season, 1).getId(), createEpisode(season, 2).getId());
    var watchedAt = Instant.parse("2026-07-07T12:00:00Z");

    watchStatusService.markWatched(userId, series.getId(), CollectableScope.SERIES, watchedAt, 0);
    watchStatusService.markWatched(userId, series.getId(), CollectableScope.SERIES, watchedAt, 0);

    assertThat(watchHistoryRepository.findByUserIdAndCollectableIdIn(userId, episodeIds))
        .hasSize(2);
  }

  @Test
  @DisplayName("Should dismiss history and delete progress when marking series unwatched")
  void shouldDismissHistoryAndDeleteProgressWhenMarkingSeriesUnwatched() {
    var userId = UUID.randomUUID();
    var series = createSeries("Reset Series");
    var season = createSeason(series, 1);
    var episode = createEpisodeWithFile(season, 1);
    var mediaFileId = episode.getFiles().iterator().next().getId();
    sessionProgressRepository.saveAndFlush(
        progressBuilder(userId, mediaFileId).positionSeconds(900).build());
    watchStatusService.markWatched(userId, series.getId());

    watchStatusService.markUnwatched(userId, series.getId());

    assertThat(
            watchHistoryRepository.findByUserIdAndCollectableIdIn(userId, List.of(episode.getId())))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry.getDismissedAt()).isNotNull());
    assertThat(watchStatusService.getWatchStatusForSeries(userId, List.of(series.getId())))
        .containsEntry(series.getId(), WatchStatus.UNWATCHED);
    assertThat(sessionProgressRepository.findByUserIdAndMediaFileIdIn(userId, Set.of(mediaFileId)))
        .isEmpty();
  }

  private Series createSeries(String title) {
    return seriesRepository.saveAndFlush(
        Series.builder().title(title).titleSort(title).library(library).build());
  }

  private Season createSeason(Series series, int seasonNumber) {
    return seasonRepository.saveAndFlush(
        Season.builder().seasonNumber(seasonNumber).series(series).library(library).build());
  }

  private Episode createEpisode(Season season, int episodeNumber) {
    return episodeRepository.saveAndFlush(
        Episode.builder().episodeNumber(episodeNumber).season(season).library(library).build());
  }

  private Episode createEpisodeWithFile(Season season, int episodeNumber) {
    var file =
        MediaFile.builder()
            .libraryId(library.getId())
            .status(MediaFileStatus.MATCHED)
            .filename("episode.mkv")
            .filepathUri("file:///media/" + UUID.randomUUID() + ".mkv")
            .build();
    return episodeRepository.saveAndFlush(
        Episode.builder()
            .episodeNumber(episodeNumber)
            .season(season)
            .library(library)
            .files(Set.of(file))
            .build());
  }
}
