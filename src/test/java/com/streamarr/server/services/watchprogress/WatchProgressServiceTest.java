package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.MediaFile;
import com.streamarr.server.domain.media.MediaFileStatus;
import com.streamarr.server.domain.media.Movie;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

  private void markAsWatched(StreamSession session) {
    // Stop at 95% to trigger watched threshold
    service.reportTimeline(
        USER_ID, session.getSessionId(), (int) (7200 * 0.95), PlaybackState.STOPPED);
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
  @DisplayName("Timeline Reporting")
  class TimelineReporting {

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
    @DisplayName("Should compute percent complete when duration available")
    void shouldComputePercentCompleteWhenDurationAvailable() {
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
    @DisplayName("Should clamp percent complete when position exceeds duration")
    void shouldClampPercentCompleteWhenPositionExceedsDuration() {
      var session = addSession(); // 120 min = 7200s

      service.reportTimeline(USER_ID, session.getSessionId(), 9999, PlaybackState.PLAYING);

      var progress =
          watchProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
              .orElseThrow();
      assertThat(progress.getPercentComplete()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Should store duration seconds when timeline reported")
    void shouldStoreDurationSecondsWhenTimelineReported() {
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

      assertThatThrownBy(
              () -> service.reportTimeline(USER_ID, unknownId, 300, PlaybackState.PLAYING))
          .isInstanceOf(SessionNotFoundException.class);
    }
  }

  @Nested
  @DisplayName("Stopped Threshold Logic")
  class StoppedThresholdLogic {

    @Test
    @DisplayName("Should delete progress when stopped below min threshold")
    void shouldDeleteProgressWhenStoppedBelowMinThreshold() {
      var session = addSession(); // 7200s duration
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
      var shortSession = StreamSessionFixture.buildSessionWithDuration(2400);
      sessionRepository.save(shortSession);

      // 2400 - 2150 = 250s remaining, clearly below maxRemainingSeconds (300)
      service.reportTimeline(USER_ID, shortSession.getSessionId(), 2150, PlaybackState.STOPPED);

      var progress =
          watchProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, shortSession.getMediaFileId())
              .orElseThrow();
      assertThat(progress.isPlayed()).isTrue();
      assertThat(progress.getLastPlayedAt()).isNotNull();
      assertThat(progress.getPositionSeconds()).isZero();
    }

    @Test
    @DisplayName("Should not delete progress when stopped at exact min threshold")
    void shouldNotDeleteProgressWhenStoppedAtExactMinThreshold() {
      var session = addSession(); // 7200s duration

      // Stop at exactly 5% (360s / 7200s) — prod uses '<', so at exactly 5% progress persists
      service.reportTimeline(USER_ID, session.getSessionId(), 360, PlaybackState.STOPPED);

      var progress =
          watchProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
              .orElseThrow();
      assertThat(progress.isPlayed()).isFalse();
      assertThat(progress.getPositionSeconds()).isEqualTo(360);
    }

    @Test
    @DisplayName("Should mark as watched when stopped at exact max percent threshold")
    void shouldMarkAsWatchedWhenStoppedAtExactMaxPercentThreshold() {
      var session = addSession(); // 7200s duration

      // Stop at exactly 90% (6480s / 7200s) — prod uses '>=', so at exactly 90% marks watched
      service.reportTimeline(USER_ID, session.getSessionId(), 6480, PlaybackState.STOPPED);

      var progress =
          watchProgressRepository
              .findByUserIdAndMediaFileId(USER_ID, session.getMediaFileId())
              .orElseThrow();
      assertThat(progress.isPlayed()).isTrue();
      assertThat(progress.getLastPlayedAt()).isNotNull();
      assertThat(progress.getPositionSeconds()).isZero();
    }

    @Test
    @DisplayName("Should mark as watched when stopped with exact max remaining seconds")
    void shouldMarkAsWatchedWhenStoppedWithExactMaxRemainingSeconds() {
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
  }

  @Nested
  @DisplayName("Already-Watched Protection")
  class AlreadyWatchedProtection {

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
  }

  @Nested
  @DisplayName("Watch Progress Events")
  class WatchProgressEvents {

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
    @DisplayName("Should publish PlaybackStoppedEvent when item already watched")
    void shouldPublishPlaybackStoppedEventWhenItemAlreadyWatched() {
      var session = addSession();
      markAsWatched(session);
      eventPublisher.getEventsOfType(PlaybackStoppedEvent.class); // clear

      // Stale session stops — PlaybackStoppedEvent should still fire
      service.reportTimeline(USER_ID, session.getSessionId(), 3600, PlaybackState.STOPPED);

      var events = eventPublisher.getEventsOfType(PlaybackStoppedEvent.class);
      assertThat(events).hasSize(2);
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
    @DisplayName("Should publish TimelineReportedEvent when progress persisted")
    void shouldPublishTimelineReportedEventWhenProgressPersisted() {
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
  }

  @Nested
  @DisplayName("Progress Retention")
  class ProgressRetention {

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
    }

    @Test
    @DisplayName("Should return empty when no progress exists")
    void shouldReturnEmptyWhenNoProgressExists() {
      var result = service.getProgress(USER_ID, UUID.randomUUID());

      assertThat(result).isEmpty();
    }
  }

  @Nested
  @DisplayName("Watch Progress Reset")
  class WatchProgressReset {

    @Test
    @DisplayName("Should delete all progress when resetting movie")
    void shouldDeleteAllProgressWhenResettingMovie() {
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

    @Test
    @DisplayName("Should delete all episode progress when resetting season")
    void shouldDeleteAllEpisodeProgressWhenResettingSeason() {
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
    @DisplayName("Should delete all episode progress when resetting series")
    void shouldDeleteAllEpisodeProgressWhenResettingSeries() {
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

      assertThat(result).containsEntry(movie.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should batch multiple collectables in single call")
    void shouldBatchMultipleCollectablesInSingleCall() {
      var movie1 = Movie.builder().build();
      movie1.setId(UUID.randomUUID());
      var movie2 = Movie.builder().build();
      movie2.setId(UUID.randomUUID());

      var mf1 = mediaFileRepository.save(createMediaFile(movie1.getId()));
      mediaFileRepository.save(createMediaFile(movie2.getId()));

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));

      var result =
          service.getWatchStatusForDirectMedia(USER_ID, List.of(movie1.getId(), movie2.getId()));

      assertThat(result).containsEntry(movie1.getId(), WatchStatus.WATCHED);
      assertThat(result).containsEntry(movie2.getId(), WatchStatus.UNWATCHED);
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
    @DisplayName("Should batch multiple seasons in single call")
    void shouldBatchMultipleSeasonsInSingleCall() {
      var s1 = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var s2 = seasonRepository.save(Season.builder().seasonNumber(2).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s1).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(1).season(s2).build());

      var mf1 = mediaFileRepository.save(createMediaFile(ep1.getId()));
      mediaFileRepository.save(createMediaFile(ep2.getId()));

      watchProgressRepository.save(buildPlayedProgress(mf1.getId()));

      var result = service.getWatchStatusForSeasons(USER_ID, List.of(s1.getId(), s2.getId()));

      assertThat(result).containsEntry(s1.getId(), WatchStatus.WATCHED);
      assertThat(result).containsEntry(s2.getId(), WatchStatus.UNWATCHED);
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

      assertThat(result).containsEntry(series.getId(), WatchStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("Should return empty map when no seasons exist")
    void shouldReturnEmptyMapWhenNoSeasonsExist() {
      var result = service.getWatchStatusForSeries(USER_ID, List.of(UUID.randomUUID()));

      assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should batch multiple series in single call")
    void shouldBatchMultipleSeriesInSingleCall() {
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

      assertThat(result).containsEntry(series1.getId(), WatchStatus.WATCHED);
      assertThat(result).containsEntry(series2.getId(), WatchStatus.UNWATCHED);
    }
  }
}
