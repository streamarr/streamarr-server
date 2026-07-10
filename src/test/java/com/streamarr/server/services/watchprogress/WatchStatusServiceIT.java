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
import com.streamarr.server.support.AuthTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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
  @Autowired private AuthTestSupport authTestSupport;

  private Library library;
  private AuthTestSupport.TestIdentity identity;
  private UUID profileId;

  @BeforeAll
  void setup() {
    library = libraryRepository.saveAndFlush(LibraryFixtureCreator.buildFakeSeriesLibrary());
    identity = authTestSupport.createIdentity();
    profileId = identity.profile().getId();
  }

  @AfterAll
  void deleteIdentitySeed() {
    authTestSupport.deleteIdentity(identity);
  }

  @Test
  @DisplayName("Should write history for every episode when marking series watched by id")
  void shouldWriteHistoryForEveryEpisodeWhenMarkingSeriesWatchedById() {
    var series = createSeries("Fully Watched Series");
    var seasonOne = createSeason(series, 1);
    var seasonTwo = createSeason(series, 2);
    var episodeIds =
        List.of(
            createEpisode(seasonOne, 1).getId(),
            createEpisode(seasonOne, 2).getId(),
            createEpisode(seasonTwo, 1).getId(),
            createEpisode(seasonTwo, 2).getId());

    watchStatusService.markWatched(profileId, series.getId());

    var history = watchHistoryRepository.findByProfileIdAndCollectableIdIn(profileId, episodeIds);
    assertThat(history)
        .extracting(WatchHistory::getCollectableId)
        .containsExactlyInAnyOrderElementsOf(episodeIds);
    assertThat(watchStatusService.getWatchStatusForSeries(profileId, List.of(series.getId())))
        .containsEntry(series.getId(), WatchStatus.WATCHED);
  }

  @Test
  @DisplayName("Should insert history once when marking series watched twice at same instant")
  void shouldInsertHistoryOnceWhenMarkingSeriesWatchedTwiceAtSameInstant() {
    var series = createSeries("Idempotent Series");
    var season = createSeason(series, 1);
    var episodeIds = List.of(createEpisode(season, 1).getId(), createEpisode(season, 2).getId());
    var watchedAt = Instant.parse("2026-07-07T12:00:00Z");

    watchStatusService.markWatched(
        profileId, series.getId(), CollectableScope.SERIES, watchedAt, 0);
    watchStatusService.markWatched(
        profileId, series.getId(), CollectableScope.SERIES, watchedAt, 0);

    assertThat(watchHistoryRepository.findByProfileIdAndCollectableIdIn(profileId, episodeIds))
        .hasSize(2);
  }

  @Test
  @DisplayName("Should dismiss history and delete progress when marking series unwatched")
  void shouldDismissHistoryAndDeleteProgressWhenMarkingSeriesUnwatched() {
    var series = createSeries("Reset Series");
    var season = createSeason(series, 1);
    var episode = createEpisodeWithFile(season, 1);
    var mediaFileId = episode.getFiles().iterator().next().getId();
    sessionProgressRepository.saveAndFlush(
        progressBuilder(profileId, mediaFileId).positionSeconds(900).build());
    watchStatusService.markWatched(profileId, series.getId());

    watchStatusService.markUnwatched(profileId, series.getId());

    assertThat(
            watchHistoryRepository.findByProfileIdAndCollectableIdIn(
                profileId, List.of(episode.getId())))
        .isNotEmpty()
        .allSatisfy(entry -> assertThat(entry.getDismissedAt()).isNotNull());
    assertThat(watchStatusService.getWatchStatusForSeries(profileId, List.of(series.getId())))
        .containsEntry(series.getId(), WatchStatus.UNWATCHED);
    assertThat(
            sessionProgressRepository.findByProfileIdAndMediaFileIdIn(
                profileId, Set.of(mediaFileId)))
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
