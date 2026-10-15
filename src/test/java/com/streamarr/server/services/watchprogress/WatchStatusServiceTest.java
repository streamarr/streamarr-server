package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildMatchedMediaFile;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildMovie;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.streaming.CollectableScope;
import com.streamarr.server.domain.streaming.WatchHistory;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeMovieRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSeriesRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.services.mutation.Outcome;
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
  private FakeMovieRepository movieRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private FakeSeriesRepository seriesRepository;
  private CapturingEventPublisher eventPublisher;
  private WatchStatusService service;

  private static final UUID PROFILE_ID = UUID.randomUUID();
  private static final Instant EARLIER = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant LATER = Instant.parse("2026-02-01T00:00:00Z");

  @BeforeEach
  void setUp() {
    sessionProgressRepository = new FakeSessionProgressRepository();
    watchHistoryRepository = new FakeWatchHistoryRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    movieRepository = new FakeMovieRepository();
    episodeRepository = new FakeEpisodeRepository();
    seasonRepository = new FakeSeasonRepository();
    seriesRepository = new FakeSeriesRepository();
    eventPublisher = new CapturingEventPublisher();
    service =
        new WatchStatusService(
            sessionProgressRepository,
            watchHistoryRepository,
            mediaFileRepository,
            movieRepository,
            episodeRepository,
            seasonRepository,
            seriesRepository,
            eventPublisher);
  }

  @Nested
  @DisplayName("Watch History")
  class WatchHistoryTests {

    @Test
    @DisplayName("Should create watch history entry when marking watched explicitly")
    void shouldCreateWatchHistoryEntryWhenMarkingWatchedExplicitly() {
      var movieId = UUID.randomUUID();

      service.markWatched(PROFILE_ID, movieId, CollectableScope.DIRECT_MEDIA, 7200);

      var history =
          watchHistoryRepository.findFirstByProfileIdAndCollectableIdOrderByWatchedAtDesc(
              PROFILE_ID, movieId);
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

      service.markWatched(PROFILE_ID, season.getId(), CollectableScope.SEASON, 3600);

      assertThat(watchHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should derive watched status from watch history")
    void shouldDeriveWatchedStatusFromWatchHistory() {
      var movie = buildMovie();
      mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));

      service.markWatched(PROFILE_ID, movie.getId(), CollectableScope.DIRECT_MEDIA, 7200);

      var result = service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId()));
      assertThat(result).containsEntry(movie.getId(), WatchStatus.WATCHED);
    }

    @Test
    @DisplayName("Should derive unwatched when latest history entry is dismissed")
    void shouldDeriveUnwatchedWhenLatestHistoryEntryIsDismissed() {
      var movie = movieRepository.save(buildMovie());
      mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));

      service.markWatched(PROFILE_ID, movie.getId(), CollectableScope.DIRECT_MEDIA, 7200);
      service.markUnwatched(PROFILE_ID, movie.getId());

      var result = service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId()));
      assertThat(result).containsEntry(movie.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should show a rewatch in progress when progress is newer than the completion")
    void shouldShowRewatchInProgressWhenProgressIsNewerThanTheCompletion() {
      var movie = buildMovie();
      var mf = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
      saveCompletion(movie.getId(), EARLIER);
      saveProgress(mf.getId(), 1800, LATER);

      assertThat(service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
      assertThat(service.getProgressForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .extractingByKey(movie.getId())
          .extracting(WatchProgressDto::positionSeconds)
          .isEqualTo(1800);
    }

    @Test
    @DisplayName("Should show the movie watched without progress when the completion is newer")
    void shouldShowMovieWatchedWithoutProgressWhenTheCompletionIsNewer() {
      var movie = buildMovie();
      var mf = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
      saveProgress(mf.getId(), 1800, EARLIER);
      saveCompletion(movie.getId(), LATER);

      assertThat(service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .containsEntry(movie.getId(), WatchStatus.WATCHED);
      assertThat(service.getProgressForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .doesNotContainKey(movie.getId());
    }

    @Test
    @DisplayName("Should show the movie watched when progress and completion share one time")
    void shouldShowMovieWatchedWhenProgressAndCompletionShareOneTime() {
      var movie = buildMovie();
      var mf = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
      saveProgress(mf.getId(), 1800, EARLIER);
      saveCompletion(movie.getId(), EARLIER);

      assertThat(service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .containsEntry(movie.getId(), WatchStatus.WATCHED);
    }

    @Test
    @DisplayName("Should count older progress again when the newer completion is dismissed")
    void shouldCountOlderProgressAgainWhenTheNewerCompletionIsDismissed() {
      var movie = buildMovie();
      var mf = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
      saveProgress(mf.getId(), 1800, EARLIER);
      var completion = saveCompletion(movie.getId(), LATER);
      completion.setDismissedAt(LATER);

      assertThat(service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Direct Media")
  class BatchWatchStatusForDirectMedia {

    @Test
    @DisplayName("Should return in progress when media files have progress")
    void shouldReturnInProgressWhenMediaFilesHaveProgress() {
      var movie = buildMovie();

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
      var mf2 = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());
      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf2.getId()).positionSeconds(600).build());

      var result = service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId()));

      assertThat(result).containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return unwatched when no progress exists")
    void shouldReturnUnwatchedWhenNoProgressExists() {
      var movie = buildMovie();

      mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));

      var result = service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId()));

      assertThat(result).containsEntry(movie.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should return in progress when some files have progress")
    void shouldReturnInProgressWhenSomeFilesHaveProgress() {
      var movie = buildMovie();

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
      mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());

      var result = service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId()));

      assertThat(result).hasSize(1).containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should batch multiple collectables in single call when given multiple IDs")
    void shouldBatchMultipleCollectablesInSingleCallWhenGivenMultipleIds() {
      var movie1 = buildMovie();
      var movie2 = buildMovie();

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(movie1.getId()));
      mediaFileRepository.save(buildMatchedMediaFile(movie2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());

      var result =
          service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie1.getId(), movie2.getId()));

      assertThat(result)
          .containsEntry(movie1.getId(), WatchStatus.IN_PROGRESS)
          .containsEntry(movie2.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should return unwatched when no media files exist")
    void shouldReturnUnwatchedWhenNoMediaFilesExist() {
      var unknownId = UUID.randomUUID();
      var result = service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(unknownId));

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

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(ep1.getId()));
      var mf2 = mediaFileRepository.save(buildMatchedMediaFile(ep2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());
      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf2.getId()).positionSeconds(600).build());

      var result = service.getWatchStatusForSeasons(PROFILE_ID, List.of(season.getId()));

      assertThat(result).containsEntry(season.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return in progress when some episodes have progress")
    void shouldReturnInProgressWhenSomeEpisodesHaveProgress() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(ep1.getId()));
      mediaFileRepository.save(buildMatchedMediaFile(ep2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());

      var result = service.getWatchStatusForSeasons(PROFILE_ID, List.of(season.getId()));

      assertThat(result).containsEntry(season.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return empty map when no episodes exist")
    void shouldReturnEmptyMapWhenNoEpisodesExist() {
      var result = service.getWatchStatusForSeasons(PROFILE_ID, List.of(UUID.randomUUID()));

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should batch multiple seasons in single call when given multiple season IDs")
    void shouldBatchMultipleSeasonsInSingleCallWhenGivenMultipleSeasonIds() {
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(ep1.getId()));
      mediaFileRepository.save(buildMatchedMediaFile(ep2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());

      var result = service.getWatchStatusForSeasons(PROFILE_ID, List.of(s1.getId(), s2.getId()));

      assertThat(result)
          .containsEntry(s1.getId(), WatchStatus.IN_PROGRESS)
          .containsEntry(s2.getId(), WatchStatus.UNWATCHED);
    }
  }

  @Nested
  @DisplayName("Scope Resolution")
  class ScopeResolution {

    @Test
    @DisplayName("Should return the movie when marking a stored movie watched by ID")
    void shouldReturnTheMovieWhenMarkingAStoredMovieWatchedById() {
      var movie = movieRepository.save(buildMovie());

      var outcome = service.markWatched(PROFILE_ID, movie.getId());

      assertThat(outcome).isEqualTo(Outcome.accepted(movie));
      assertThat(service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .containsEntry(movie.getId(), WatchStatus.WATCHED);
    }

    @Test
    @DisplayName("Should reject with item not found when marking an unknown ID watched")
    void shouldRejectWithItemNotFoundWhenMarkingAnUnknownIdWatched() {
      var unknownId = UUID.randomUUID();

      var outcome = service.markWatched(PROFILE_ID, unknownId);

      assertThat(outcome)
          .isEqualTo(Outcome.rejected(new MarkWatchedRejection.ItemNotFound(unknownId)));
      assertThat(watchHistoryRepository.count()).isZero();
      assertThat(eventPublisher.getEventsOfType(Object.class)).isEmpty();
    }

    @Test
    @DisplayName("Should return the movie when marking a stored movie unwatched by ID")
    void shouldReturnTheMovieWhenMarkingAStoredMovieUnwatchedById() {
      var movie = movieRepository.save(buildMovie());
      service.markWatched(PROFILE_ID, movie.getId());

      var outcome = service.markUnwatched(PROFILE_ID, movie.getId());

      assertThat(outcome).isEqualTo(Outcome.accepted(movie));
      assertThat(service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .containsEntry(movie.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should reject with item not found when marking an unknown ID unwatched")
    void shouldRejectWithItemNotFoundWhenMarkingAnUnknownIdUnwatched() {
      var unknownId = UUID.randomUUID();

      var outcome = service.markUnwatched(PROFILE_ID, unknownId);

      assertThat(outcome)
          .isEqualTo(Outcome.rejected(new MarkUnwatchedRejection.ItemNotFound(unknownId)));
      assertThat(eventPublisher.getEventsOfType(Object.class)).isEmpty();
    }

    @Test
    @DisplayName("Should mark every episode when marking a stored series watched by ID")
    void shouldMarkEveryEpisodeWhenMarkingAStoredSeriesWatchedById() {
      var series = seriesRepository.save(buildSeries());
      var season = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      var outcome = service.markWatched(PROFILE_ID, series.getId());

      assertThat(outcome).isEqualTo(Outcome.accepted(series));
      assertThat(service.getWatchStatusForSeries(PROFILE_ID, List.of(series.getId())))
          .containsEntry(series.getId(), WatchStatus.WATCHED);
    }

    @Test
    @DisplayName("Should write no history when marking a season without episodes watched by ID")
    void shouldWriteNoHistoryWhenMarkingASeasonWithoutEpisodesWatchedById() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());

      var outcome = service.markWatched(PROFILE_ID, season.getId());

      assertThat(outcome).isEqualTo(Outcome.accepted(season));
      assertThat(watchHistoryRepository.count()).isZero();
    }

    @Test
    @DisplayName("Should resolve season scope when marking season watched by id")
    void shouldResolveSeasonScopeWhenMarkingSeasonWatchedById() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      service.markWatched(PROFILE_ID, season.getId());

      assertThat(watchHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should dismiss all episode history when marking season unwatched explicitly")
    void shouldDismissAllEpisodeHistoryWhenMarkingSeasonUnwatchedExplicitly() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var first = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var second =
          episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());
      service.markWatched(PROFILE_ID, season.getId(), CollectableScope.SEASON, 3600);

      service.markUnwatched(PROFILE_ID, season.getId(), CollectableScope.SEASON);

      var history =
          watchHistoryRepository.findByProfileIdAndCollectableIdIn(
              PROFILE_ID, List.of(first.getId(), second.getId()));
      assertThat(history)
          .hasSize(2)
          .allSatisfy(entry -> assertThat(entry.getDismissedAt()).isNotNull());
      assertThat(service.getWatchStatusForSeasons(PROFILE_ID, List.of(season.getId())))
          .containsEntry(season.getId(), WatchStatus.UNWATCHED);
    }
  }

  @Nested
  @DisplayName("Aggregate History Derivation")
  class AggregateHistoryDerivation {

    @Test
    @DisplayName("Should return watched when every episode across seasons has history")
    void shouldReturnWatchedWhenEveryEpisodeAcrossSeasonsHasHistory() {
      var series = buildSeries();
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
      episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      service.markWatched(PROFILE_ID, series.getId(), CollectableScope.SERIES, 3600);

      var result = service.getWatchStatusForSeries(PROFILE_ID, List.of(series.getId()));
      assertThat(result).containsEntry(series.getId(), WatchStatus.WATCHED);
    }

    @Test
    @DisplayName("Should return in progress when only some episodes have history")
    void shouldReturnInProgressWhenOnlySomeEpisodesHaveHistory() {
      var series = buildSeries();
      var season = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var watched =
          episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      service.markWatched(PROFILE_ID, watched.getId(), CollectableScope.DIRECT_MEDIA, 3600);

      var result = service.getWatchStatusForSeries(PROFILE_ID, List.of(series.getId()));
      assertThat(result).containsEntry(series.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return unwatched when series has no episodes or no watch history")
    void shouldReturnUnwatchedWhenSeriesHasNoEpisodesOrNoHistory() {
      var populated = buildSeries();
      var populatedSeason =
          seasonRepository.save(Season.builder().seasonNumber(1).series(populated).build());
      episodeRepository.save(Episode.builder().episodeNumber(1).season(populatedSeason).build());

      var empty = buildSeries();
      seasonRepository.save(Season.builder().seasonNumber(1).series(empty).build());

      var result =
          service.getWatchStatusForSeries(PROFILE_ID, List.of(populated.getId(), empty.getId()));

      assertThat(result)
          .containsEntry(populated.getId(), WatchStatus.UNWATCHED)
          .containsEntry(empty.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName("Should return unwatched when session progress position is zero")
    void shouldReturnUnwatchedWhenSessionProgressPositionIsZero() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var episode =
          episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var file = mediaFileRepository.save(buildMatchedMediaFile(episode.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, file.getId()).positionSeconds(0).build());

      var result = service.getWatchStatusForSeasons(PROFILE_ID, List.of(season.getId()));
      assertThat(result).containsEntry(season.getId(), WatchStatus.UNWATCHED);
    }

    @Test
    @DisplayName(
        "Should resume at the latest session with a position when the newest session has none")
    void shouldResumeAtLatestSessionWithAPositionWhenTheNewestSessionHasNone() {
      var movie = buildMovie();
      var file = mediaFileRepository.save(buildMatchedMediaFile(movie.getId()));
      saveProgress(file.getId(), 900, EARLIER);
      saveProgress(file.getId(), 0, LATER);

      assertThat(service.getWatchStatusForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
      assertThat(service.getProgressForDirectMedia(PROFILE_ID, List.of(movie.getId())))
          .extractingByKey(movie.getId())
          .extracting(WatchProgressDto::positionSeconds)
          .isEqualTo(900);
    }

    @Test
    @DisplayName("Should show the season in progress when a watched episode is being rewatched")
    void shouldShowSeasonInProgressWhenAWatchedEpisodeIsBeingRewatched() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var episode =
          episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var file = mediaFileRepository.save(buildMatchedMediaFile(episode.getId()));
      saveCompletion(episode.getId(), EARLIER);
      saveProgress(file.getId(), 600, LATER);

      assertThat(service.getWatchStatusForSeasons(PROFILE_ID, List.of(season.getId())))
          .containsEntry(season.getId(), WatchStatus.IN_PROGRESS);
      assertThat(service.getAggregateProgressForSeasons(PROFILE_ID, List.of(season.getId())))
          .extractingByKey(season.getId())
          .extracting(WatchProgressDto::positionSeconds)
          .isEqualTo(600);
    }

    @Test
    @DisplayName("Should show the season watched without progress when completions are newer")
    void shouldShowSeasonWatchedWithoutProgressWhenCompletionsAreNewer() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var episode =
          episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var file = mediaFileRepository.save(buildMatchedMediaFile(episode.getId()));
      saveProgress(file.getId(), 600, EARLIER);
      saveCompletion(episode.getId(), LATER);

      assertThat(service.getWatchStatusForSeasons(PROFILE_ID, List.of(season.getId())))
          .containsEntry(season.getId(), WatchStatus.WATCHED);
      assertThat(service.getAggregateProgressForSeasons(PROFILE_ID, List.of(season.getId())))
          .doesNotContainKey(season.getId());
    }
  }

  @Nested
  @DisplayName("Batch Watch Status for Series")
  class BatchWatchStatusForSeries {

    @Test
    @DisplayName("Should return in progress when all episodes across seasons have progress")
    void shouldReturnInProgressWhenAllEpisodesAcrossSeasonsHaveProgress() {
      var series = buildSeries();
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(ep1.getId()));
      var mf2 = mediaFileRepository.save(buildMatchedMediaFile(ep2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());
      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf2.getId()).positionSeconds(600).build());

      var result = service.getWatchStatusForSeries(PROFILE_ID, List.of(series.getId()));

      assertThat(result).containsEntry(series.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return in progress when some episodes have progress")
    void shouldReturnInProgressWhenSomeEpisodesHaveProgress() {
      var series = buildSeries();
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(ep1.getId()));
      mediaFileRepository.save(buildMatchedMediaFile(ep2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());

      var result = service.getWatchStatusForSeries(PROFILE_ID, List.of(series.getId()));

      assertThat(result).hasSize(1).containsEntry(series.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return empty map when no seasons exist")
    void shouldReturnEmptyMapWhenNoSeasonsExist() {
      var result = service.getWatchStatusForSeries(PROFILE_ID, List.of(UUID.randomUUID()));

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should batch multiple series in single call when given multiple series IDs")
    void shouldBatchMultipleSeriesInSingleCallWhenGivenMultipleSeriesIds() {
      var series1 = buildSeries();
      var series2 = buildSeries();
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series1).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(1).series(series2).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(ep1.getId()));
      mediaFileRepository.save(buildMatchedMediaFile(ep2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());

      var result =
          service.getWatchStatusForSeries(PROFILE_ID, List.of(series1.getId(), series2.getId()));

      assertThat(result)
          .containsEntry(series1.getId(), WatchStatus.IN_PROGRESS)
          .containsEntry(series2.getId(), WatchStatus.UNWATCHED);
    }
  }

  private void saveProgress(UUID mediaFileId, int positionSeconds, Instant lastModifiedOn) {
    var progress =
        sessionProgressRepository.save(
            progressBuilder(PROFILE_ID, mediaFileId).positionSeconds(positionSeconds).build());
    AuditFieldSetter.setLastModifiedOn(progress, lastModifiedOn);
  }

  private WatchHistory saveCompletion(UUID collectableId, Instant watchedAt) {
    return watchHistoryRepository.save(
        WatchHistory.builder()
            .profileId(PROFILE_ID)
            .collectableId(collectableId)
            .watchedAt(watchedAt)
            .durationSeconds(7200)
            .build());
  }
}
