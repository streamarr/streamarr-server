package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.media.Series;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.domain.streaming.WatchStatus;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeStreamSessionRepository;
import com.streamarr.server.fakes.FakeWatchProgressRepository;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.watchprogress.events.MediaWatchedEvent;
import com.streamarr.server.services.watchprogress.events.PlaybackStoppedEvent;
import com.streamarr.server.services.watchprogress.events.TimelineReportedEvent;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Watch Progress Service Tests")
class WatchProgressServiceTest {

  private FakeStreamSessionRepository sessionRepository;
  private FakeWatchProgressRepository watchProgressRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private CapturingEventPublisher eventPublisher;
  private WatchProgressService service;

  private static final UUID USER_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    sessionRepository = new FakeStreamSessionRepository();
    watchProgressRepository = new FakeWatchProgressRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    episodeRepository = new FakeEpisodeRepository();
    seasonRepository = new FakeSeasonRepository();
    eventPublisher = new CapturingEventPublisher();
    var properties = new WatchProgressProperties(5.0, 90.0, 300);
    service =
        new WatchProgressService(
            sessionRepository,
            watchProgressRepository,
            mediaFileRepository,
            episodeRepository,
            seasonRepository,
            properties,
            eventPublisher);
  }

  private StreamSession addSession() {
    var session = StreamSessionFixture.buildMpegtsSession();
    sessionRepository.save(session);
    return session;
  }

  @Test
  @DisplayName("Should update session snapshot position when timeline reported")
  void shouldUpdateSessionSnapshotPositionWhenTimelineReported() {
    var session = addSession();

    service.reportTimeline(USER_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

    assertThat(session.getSeekPosition()).isEqualTo(300);
  }

  @Test
  @DisplayName("Should update session snapshot state when timeline reported")
  void shouldUpdateSessionSnapshotStateWhenTimelineReported() {
    var session = addSession();

    service.reportTimeline(USER_ID, session.getSessionId(), 300, PlaybackState.PAUSED);

    assertThat(session.getPlaybackSnapshot().state()).isEqualTo(PlaybackState.PAUSED);
  }

  @Test
  @DisplayName("Should persist watch progress when timeline reported")
  void shouldPersistWatchProgressWhenTimelineReported() {
    var session = addSession();

    service.reportTimeline(USER_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

    var progress =
        watchProgressRepository.findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId());
    assertThat(progress).isPresent();
    assertThat(progress.get().getPositionSeconds()).isEqualTo(300);
    assertThat(progress.get().getUserId()).isEqualTo(USER_ID);
    assertThat(progress.get().getMediaFileId()).isEqualTo(session.getMediaFileId());
  }

  @Test
  @DisplayName("Should compute percent complete from duration")
  void shouldComputePercentCompleteFromDuration() {
    var session = addSession(); // 120 min duration

    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.getPercentComplete()).isCloseTo(50.0, within(0.1));
  }

  @Test
  @DisplayName("Should update existing progress when reported again")
  void shouldUpdateExistingProgressWhenReportedAgain() {
    var session = addSession();

    service.reportTimeline(USER_ID, session.getSessionId(), 300, PlaybackState.PLAYING);
    service.reportTimeline(USER_ID, session.getSessionId(), 600, PlaybackState.PLAYING);

    assertThat(watchProgressRepository.count()).isEqualTo(1);
    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.getPositionSeconds()).isEqualTo(600);
  }

  @Test
  @DisplayName("Should clamp percent complete to 100")
  void shouldClampPercentCompleteTo100() {
    var session = addSession(); // 120 min = 7200s

    service.reportTimeline(USER_ID, session.getSessionId(), 9999, PlaybackState.PLAYING);

    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.getPercentComplete()).isEqualTo(100.0);
  }

  @Test
  @DisplayName("Should store duration seconds from media probe")
  void shouldStoreDurationSecondsFromMediaProbe() {
    var session = addSession(); // 120 min = 7200s

    service.reportTimeline(USER_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.getDurationSeconds()).isEqualTo(7200);
  }

  @Test
  @DisplayName("Should return early when duration is zero")
  void shouldReturnEarlyWhenDurationIsZero() {
    var session = StreamSessionFixture.buildZeroDurationSession();
    sessionRepository.save(session);

    service.reportTimeline(USER_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

    assertThat(watchProgressRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should throw when session not found")
  void shouldThrowWhenSessionNotFound() {
    var unknownId = UUID.randomUUID();

    assertThatThrownBy(() -> service.reportTimeline(USER_ID, unknownId, 300, PlaybackState.PLAYING))
        .isInstanceOf(SessionNotFoundException.class);
  }

  // --- STOPPED threshold tests ---

  @Test
  @DisplayName("Should delete progress when stopped below min threshold")
  void shouldDeleteProgressWhenStoppedBelowMinThreshold() {
    var session = addSession(); // 7200s duration
    // Seed existing progress
    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);
    assertThat(watchProgressRepository.count()).isEqualTo(1);

    // Stop at 2% (144s / 7200s) — below 5% threshold
    service.reportTimeline(USER_ID, session.getSessionId(), 144, PlaybackState.STOPPED);

    assertThat(watchProgressRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should set lastPlayedAt when stopped above max percent threshold")
  void shouldSetLastPlayedAtWhenStoppedAboveMaxPercentThreshold() {
    var session = addSession(); // 7200s duration

    // Stop at 93% (6696s / 7200s) — above 90% threshold
    service.reportTimeline(USER_ID, session.getSessionId(), 6696, PlaybackState.STOPPED);

    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.isPlayed()).isTrue();
    assertThat(progress.getLastPlayedAt()).isNotNull();
    assertThat(progress.getPositionSeconds()).isZero();
  }

  @Test
  @DisplayName("Should set lastPlayedAt when remaining time below max remaining seconds")
  void shouldSetLastPlayedAtWhenRemainingTimeBelowMaxRemainingSeconds() {
    var session = addSession(); // 7200s duration, maxRemainingSeconds=300

    // Stop at 6950s — 250s remaining (< 300s threshold), but only ~96.5% (also > 90%)
    // Use a position where percent < 90% but remaining < 300s to test time threshold
    // Actually with 7200s, 90% = 6480s. So 6901s is ~95.8%. Let me use a long movie instead.
    // With 7200s and maxRemaining=300: the time threshold triggers at 6900s (remaining=300).
    // 6900/7200 = 95.8% which is also > 90%. To isolate the time threshold, I need a scenario
    // where percent < 90% but remaining < 300.
    // That requires duration < 3000s (5min threshold / (1 - 0.9) = 3000s).
    // With a 40-min movie (2400s): 90% = 2160s. Remaining=300s at 2100s = 87.5%.
    // So stopping a 40-min movie at 2100s (87.5%, 300s remaining) should trigger via time.
    var shortSession = StreamSessionFixture.buildSessionWithDuration(2400);
    sessionRepository.save(shortSession);

    service.reportTimeline(USER_ID, shortSession.getSessionId(), 2100, PlaybackState.STOPPED);

    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, shortSession.getMediaFileId())
            .orElseThrow();
    assertThat(progress.isPlayed()).isTrue();
    assertThat(progress.getLastPlayedAt()).isNotNull();
    assertThat(progress.getPositionSeconds()).isZero();
  }

  @Test
  @DisplayName("Should persist normally when stopped between thresholds")
  void shouldPersistNormallyWhenStoppedBetweenThresholds() {
    var session = addSession(); // 7200s duration

    // Stop at 50% (3600s / 7200s) — between 5% and 90%
    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.STOPPED);

    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.isPlayed()).isFalse();
    assertThat(progress.getPositionSeconds()).isEqualTo(3600);
  }

  @Test
  @DisplayName("Should not apply thresholds when playing")
  void shouldNotApplyThresholdsWhenPlaying() {
    var session = addSession(); // 7200s duration

    // Report at 1% while PLAYING — should persist (not deleted)
    service.reportTimeline(USER_ID, session.getSessionId(), 72, PlaybackState.PLAYING);

    assertThat(watchProgressRepository.count()).isEqualTo(1);
    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.getPositionSeconds()).isEqualTo(72);
  }

  @Test
  @DisplayName("Should not apply thresholds when paused")
  void shouldNotApplyThresholdsWhenPaused() {
    var session = addSession(); // 7200s duration

    // Report at 95% while PAUSED — should not mark as watched
    service.reportTimeline(USER_ID, session.getSessionId(), 6840, PlaybackState.PAUSED);

    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.isPlayed()).isFalse();
    assertThat(progress.getPositionSeconds()).isEqualTo(6840);
  }

  private WatchProgress seedProgress(StreamSession session, int positionSeconds) {
    service.reportTimeline(USER_ID, session.getSessionId(), positionSeconds, PlaybackState.PLAYING);
    return watchProgressRepository
        .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
        .orElseThrow();
  }

  private void markAsWatched(StreamSession session) {
    // Stop at 95% to trigger watched threshold
    service.reportTimeline(
        USER_ID, session.getSessionId(), (int) (7200 * 0.95), PlaybackState.STOPPED);
  }

  // --- Stale-session guard tests ---

  @Test
  @DisplayName("Should skip write when existing progress has lastPlayedAt")
  void shouldSkipWriteWhenExistingProgressHasLastPlayedAt() {
    var session = addSession();
    markAsWatched(session);

    var progressBefore =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progressBefore.isPlayed()).isTrue();

    // Stale session reports PLAYING at 50% — should be ignored
    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

    var progressAfter =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progressAfter.isPlayed()).isTrue();
    assertThat(progressAfter.getPositionSeconds()).isZero();
  }

  // --- Event tests ---

  @Test
  @DisplayName("Should publish PlaybackStoppedEvent when stopped")
  void shouldPublishPlaybackStoppedEventWhenStopped() {
    var session = addSession();

    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.STOPPED);

    var events = eventPublisher.getEventsOfType(PlaybackStoppedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().mediaFileId()).isEqualTo(session.getMediaFileId());
    assertThat(events.getFirst().positionSeconds()).isEqualTo(3600);
  }

  @Test
  @DisplayName("Should publish PlaybackStoppedEvent even when item already watched")
  void shouldPublishPlaybackStoppedEventEvenWhenItemAlreadyWatched() {
    var session = addSession();
    markAsWatched(session);
    eventPublisher.getEventsOfType(PlaybackStoppedEvent.class); // clear

    // Stale session stops — PlaybackStoppedEvent should still fire
    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.STOPPED);

    var events = eventPublisher.getEventsOfType(PlaybackStoppedEvent.class);
    assertThat(events).hasSizeGreaterThanOrEqualTo(2);
  }

  @Test
  @DisplayName("Should not publish PlaybackStoppedEvent when playing")
  void shouldNotPublishPlaybackStoppedEventWhenPlaying() {
    var session = addSession();

    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

    var events = eventPublisher.getEventsOfType(PlaybackStoppedEvent.class);
    assertThat(events).isEmpty();
  }

  @Test
  @DisplayName("Should publish TimelineReportedEvent")
  void shouldPublishTimelineReportedEvent() {
    var session = addSession();

    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

    var events = eventPublisher.getEventsOfType(TimelineReportedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().positionSeconds()).isEqualTo(3600);
    assertThat(events.getFirst().state()).isEqualTo(PlaybackState.PLAYING);
  }

  @Test
  @DisplayName("Should publish MediaWatchedEvent when lastPlayedAt transitions")
  void shouldPublishMediaWatchedEventWhenLastPlayedAtTransitions() {
    var session = addSession();

    // Stop at 95% to trigger watched
    service.reportTimeline(
        USER_ID, session.getSessionId(), (int) (7200 * 0.95), PlaybackState.STOPPED);

    var events = eventPublisher.getEventsOfType(MediaWatchedEvent.class);
    assertThat(events).hasSize(1);
    assertThat(events.getFirst().mediaFileId()).isEqualTo(session.getMediaFileId());
  }

  @Test
  @DisplayName("Should not publish MediaWatchedEvent when already watched")
  void shouldNotPublishMediaWatchedEventWhenAlreadyWatched() {
    var session = addSession();
    markAsWatched(session);

    var eventsBefore = eventPublisher.getEventsOfType(MediaWatchedEvent.class);
    assertThat(eventsBefore).hasSize(1);

    // Stale session reports — guard blocks write, no new event
    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

    var eventsAfter = eventPublisher.getEventsOfType(MediaWatchedEvent.class);
    assertThat(eventsAfter).hasSize(1); // unchanged
  }

  @Test
  @DisplayName("Should not publish events when progress deleted below threshold")
  void shouldNotPublishEventsWhenProgressDeletedBelowThreshold() {
    var session = addSession();

    // Stop at 1% — below min threshold, deletes progress
    service.reportTimeline(USER_ID, session.getSessionId(), 72, PlaybackState.STOPPED);

    var timelineEvents = eventPublisher.getEventsOfType(TimelineReportedEvent.class);
    assertThat(timelineEvents).isEmpty();

    // But PlaybackStoppedEvent should still fire
    var stoppedEvents = eventPublisher.getEventsOfType(PlaybackStoppedEvent.class);
    assertThat(stoppedEvents).hasSize(1);
  }

  @Test
  @DisplayName("Should retain last progress when session destroyed without stopped")
  void shouldRetainLastProgressWhenSessionDestroyedWithoutStopped() {
    var session = addSession();

    // Report PLAYING at 50%
    service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

    // Session destroyed without STOPPED report (client crash)
    sessionRepository.removeById(session.getSessionId());

    // Progress should still be in DB
    var progress =
        watchProgressRepository
            .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
            .orElseThrow();
    assertThat(progress.getPositionSeconds()).isEqualTo(3600);
  }

  // --- resetProgress tests ---

  @Test
  @DisplayName("Should reset progress for movie")
  void shouldResetProgressForMovie() {
    var session = addSession();
    var movieId = UUID.randomUUID();
    mediaFileRepository.save(
        MediaFile.builder()
            .mediaId(movieId)
            .filename("movie.mkv")
            .filepathUri("/media/movie.mkv")
            .size(1_000_000)
            .status(MediaFileStatus.MATCHED)
            .build());
    var mediaFile = mediaFileRepository.findByMediaId(movieId).getFirst();

    watchProgressRepository.save(
        WatchProgress.builder()
            .userId(USER_ID)
            .mediaFileId(mediaFile.getId())
            .positionSeconds(3600)
            .percentComplete(50.0)
            .durationSeconds(7200)
            .build());
    assertThat(watchProgressRepository.count()).isEqualTo(1);

    service.resetProgress(USER_ID, movieId);

    assertThat(watchProgressRepository.count()).isZero();
  }

  // --- deriveWatchStatus tests ---

  @Test
  @DisplayName("Should derive watched status when all items watched")
  void shouldDeriveWatchedStatusWhenAllItemsWatched() {
    assertThat(WatchProgressService.deriveWatchStatus(5, 5, 0)).isEqualTo(WatchStatus.WATCHED);
  }

  @Test
  @DisplayName("Should derive in progress status when some items watched")
  void shouldDeriveInProgressStatusWhenSomeItemsWatched() {
    assertThat(WatchProgressService.deriveWatchStatus(5, 2, 1)).isEqualTo(WatchStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("Should derive unwatched status when no progress")
  void shouldDeriveUnwatchedStatusWhenNoProgress() {
    assertThat(WatchProgressService.deriveWatchStatus(5, 0, 0)).isEqualTo(WatchStatus.UNWATCHED);
  }

  @Test
  @DisplayName("Should derive in progress from partial episode progress")
  void shouldDeriveInProgressFromPartialEpisodeProgress() {
    assertThat(WatchProgressService.deriveWatchStatus(5, 0, 2)).isEqualTo(WatchStatus.IN_PROGRESS);
  }

  @Test
  @DisplayName("Should derive unwatched when total items is zero")
  void shouldDeriveUnwatchedWhenTotalItemsIsZero() {
    assertThat(WatchProgressService.deriveWatchStatus(0, 0, 0)).isEqualTo(WatchStatus.UNWATCHED);
  }

  // --- Season/Series reset and watchStatus tests ---

  @Test
  @DisplayName("Should reset progress for season by cascading through episodes")
  void shouldResetProgressForSeasonByCascadingThroughEpisodes() {
    var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
    var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
    var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

    var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
    var mf2 = mediaFileRepository.save(createMediaFile(ep2.getId()));

    watchProgressRepository.save(buildProgress(mf1.getId(), 300));
    watchProgressRepository.save(buildProgress(mf2.getId(), 600));
    assertThat(watchProgressRepository.count()).isEqualTo(2);

    service.resetProgress(USER_ID, season.getId());

    assertThat(watchProgressRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should reset progress for series by cascading through seasons and episodes")
  void shouldResetProgressForSeriesByCascadingThroughSeasonsAndEpisodes() {
    var series = Series.builder().build();
    series.setId(UUID.randomUUID());
    var s1 = seasonRepository.save(Season.builder().seasonNumber(1).series(series).build());
    var s2 = seasonRepository.save(Season.builder().seasonNumber(2).series(series).build());
    var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
    var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

    var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
    var mf2 = mediaFileRepository.save(createMediaFile(ep2.getId()));

    watchProgressRepository.save(buildProgress(mf1.getId(), 300));
    watchProgressRepository.save(buildProgress(mf2.getId(), 600));
    assertThat(watchProgressRepository.count()).isEqualTo(2);

    service.resetProgress(USER_ID, series.getId());

    assertThat(watchProgressRepository.count()).isZero();
  }

  @Test
  @DisplayName("Should derive watch status for season from episodes")
  void shouldDeriveWatchStatusForSeasonFromEpisodes() {
    var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
    var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
    var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

    var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
    mediaFileRepository.save(createMediaFile(ep2.getId()));

    watchProgressRepository.save(buildProgress(mf1.getId(), 300));

    assertThat(service.getWatchStatusForCollectable(USER_ID, season.getId()))
        .isEqualTo(WatchStatus.IN_PROGRESS);
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
}
