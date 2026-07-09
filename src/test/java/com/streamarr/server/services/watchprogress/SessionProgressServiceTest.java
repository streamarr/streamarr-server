package com.streamarr.server.services.watchprogress;

import static com.streamarr.server.fixtures.MediaEntityFixture.buildMatchedMediaFile;
import static com.streamarr.server.fixtures.MediaEntityFixture.buildSeries;
import static com.streamarr.server.fixtures.SessionProgressFixture.progressBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Season;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeEpisodeRepository;
import com.streamarr.server.fakes.FakeMediaFileRepository;
import com.streamarr.server.fakes.FakeSeasonRepository;
import com.streamarr.server.fakes.FakeSessionProgressRepository;
import com.streamarr.server.fakes.FakeStreamSessionRepository;
import com.streamarr.server.fakes.FakeWatchHistoryRepository;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.watchprogress.events.ItemWatchedEvent;
import com.streamarr.server.services.watchprogress.events.SessionProgressChangedEvent;
import com.streamarr.server.services.watchprogress.events.WatchStatusChangedEvent;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Session Progress Service Tests")
class SessionProgressServiceTest {

  private FakeStreamSessionRepository sessionRepository;
  private FakeSessionProgressRepository sessionProgressRepository;
  private FakeWatchHistoryRepository watchHistoryRepository;
  private FakeMediaFileRepository mediaFileRepository;
  private FakeEpisodeRepository episodeRepository;
  private FakeSeasonRepository seasonRepository;
  private CapturingEventPublisher eventPublisher;
  private WatchStatusService watchStatusService;
  private SessionProgressService service;

  private static final UUID PROFILE_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    sessionRepository = new FakeStreamSessionRepository();
    sessionProgressRepository = new FakeSessionProgressRepository();
    watchHistoryRepository = new FakeWatchHistoryRepository();
    mediaFileRepository = new FakeMediaFileRepository();
    episodeRepository = new FakeEpisodeRepository();
    seasonRepository = new FakeSeasonRepository();
    eventPublisher = new CapturingEventPublisher();
    watchStatusService =
        new WatchStatusService(
            sessionProgressRepository,
            watchHistoryRepository,
            mediaFileRepository,
            episodeRepository,
            seasonRepository,
            eventPublisher);
    var properties = new WatchProgressProperties(5.0, 90.0, 300);
    service =
        new SessionProgressService(
            sessionRepository,
            sessionProgressRepository,
            mediaFileRepository,
            properties,
            watchStatusService,
            eventPublisher);
  }

  private StreamSession addSession() {
    var session = StreamSessionFixture.buildMpegtsSessionOwnedBy(PROFILE_ID);
    sessionRepository.save(session);
    saveMediaFileForSession(session);
    return session;
  }

  private void saveMediaFileForSession(StreamSession session) {
    var mediaFile = buildMatchedMediaFile(UUID.randomUUID());
    mediaFile.setId(session.getMediaFileId());
    mediaFileRepository.save(mediaFile);
  }

  private void markAsWatched(StreamSession session) {
    // Stop at 95% to trigger watched threshold
    service.reportStreamSessionTimeline(
        PROFILE_ID, session.getSessionId(), (int) (7200 * 0.95), PlaybackState.STOPPED);
  }

  @Nested
  @DisplayName("Timeline Reporting")
  class TimelineReporting {

    @Test
    @DisplayName("Should update session snapshot position when timeline reported")
    void shouldUpdateSessionSnapshotPositionWhenTimelineReported() {
      var session = addSession();

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

      assertThat(session.getPlaybackSnapshot().positionSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("Should update session snapshot state when timeline reported")
    void shouldUpdateSessionSnapshotStateWhenTimelineReported() {
      var session = addSession();

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 300, PlaybackState.PAUSED);

      assertThat(session.getPlaybackSnapshot().state()).isEqualTo(PlaybackState.PAUSED);
    }

    @Test
    @DisplayName("Should persist watch progress when timeline reported")
    void shouldPersistWatchProgressWhenTimelineReported() {
      var session = addSession();

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

      var progress = sessionProgressRepository.findBySessionId(session.getSessionId());
      assertThat(progress).isPresent();
      assertThat(progress.get().getPositionSeconds()).isEqualTo(300);
      assertThat(progress.get().getProfileId()).isEqualTo(PROFILE_ID);
      assertThat(progress.get().getMediaFileId()).isEqualTo(session.getMediaFileId());
    }

    @Test
    @DisplayName("Should compute percent complete when duration available")
    void shouldComputePercentCompleteWhenDurationAvailable() {
      var session = addSession(); // 120 min duration

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

      var progress =
          sessionProgressRepository.findBySessionId(session.getSessionId()).orElseThrow();
      assertThat(progress.getPercentComplete()).isCloseTo(50.0, within(0.1));
    }

    @Test
    @DisplayName("Should update existing progress when reported again")
    void shouldUpdateExistingProgressWhenReportedAgain() {
      var session = addSession();

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 300, PlaybackState.PLAYING);
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 600, PlaybackState.PLAYING);

      assertThat(sessionProgressRepository.findBySessionId(session.getSessionId())).isPresent();
      var progress =
          sessionProgressRepository.findBySessionId(session.getSessionId()).orElseThrow();
      assertThat(progress.getPositionSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("Should clamp percent complete when position exceeds duration")
    void shouldClampPercentCompleteWhenPositionExceedsDuration() {
      var session = addSession(); // 120 min = 7200s

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 9999, PlaybackState.PLAYING);

      var progress =
          sessionProgressRepository.findBySessionId(session.getSessionId()).orElseThrow();
      assertThat(progress.getPercentComplete()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Should store duration seconds when timeline reported")
    void shouldStoreDurationSecondsWhenTimelineReported() {
      var session = addSession(); // 120 min = 7200s

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

      var progress =
          sessionProgressRepository.findBySessionId(session.getSessionId()).orElseThrow();
      assertThat(progress.getDurationSeconds()).isEqualTo(7200);
    }

    @Test
    @DisplayName("Should return early when duration is zero")
    void shouldReturnEarlyWhenDurationIsZero() {
      var session = StreamSessionFixture.zeroDurationSessionBuilder().profileId(PROFILE_ID).build();
      sessionRepository.save(session);

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 300, PlaybackState.PLAYING);

      assertThat(sessionProgressRepository.count()).isZero();
    }

    @Test
    @DisplayName("Should throw when session not found")
    void shouldThrowWhenSessionNotFound() {
      var unknownId = UUID.randomUUID();

      assertThatThrownBy(
              () ->
                  service.reportStreamSessionTimeline(
                      PROFILE_ID, unknownId, 300, PlaybackState.PLAYING))
          .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    @DisplayName("Should throw when reporting timeline for session owned by another profile")
    void shouldThrowWhenReportingTimelineForSessionOwnedByAnotherProfile() {
      var session = addSession();
      var sessionId = session.getSessionId();
      var otherProfileId = UUID.randomUUID();

      assertThatThrownBy(
              () ->
                  service.reportStreamSessionTimeline(
                      otherProfileId, sessionId, 300, PlaybackState.PLAYING))
          .isInstanceOf(SessionNotFoundException.class);

      assertThat(sessionProgressRepository.count()).isZero();
    }

    @Test
    @DisplayName("Should log ownership miss when timeline reported by another profile")
    void shouldLogOwnershipMissWhenTimelineReportedByAnotherProfile() {
      var session = addSession();
      var sessionId = session.getSessionId();
      var otherProfileId = UUID.randomUUID();

      var logger = (Logger) LoggerFactory.getLogger(SessionProgressService.class);
      var appender = new ListAppender<ILoggingEvent>();
      appender.start();
      logger.addAppender(appender);
      try {
        assertThatThrownBy(
                () ->
                    service.reportStreamSessionTimeline(
                        otherProfileId, sessionId, 300, PlaybackState.PLAYING))
            .isInstanceOf(SessionNotFoundException.class);
      } finally {
        logger.detachAppender(appender);
      }

      assertThat(appender.list)
          .filteredOn(event -> event.getLevel() == Level.WARN)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anyMatch(message -> message.contains(sessionId.toString()));
    }

    @Test
    @DisplayName("Should not delete owner progress when stop reported by another profile")
    void shouldNotDeleteOwnerProgressWhenStopReportedByAnotherProfile() {
      var session = addSession();
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

      // A below-min-threshold STOPPED report would DISCARD the owner's resume point if
      // ownership were not enforced
      var sessionId = session.getSessionId();
      var otherProfileId = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  service.reportStreamSessionTimeline(
                      otherProfileId, sessionId, 72, PlaybackState.STOPPED))
          .isInstanceOf(SessionNotFoundException.class);

      assertThat(sessionProgressRepository.findBySessionId(session.getSessionId())).isPresent();
    }

    @Test
    @DisplayName("Should not mark watched when stopped above watched threshold by another profile")
    void shouldNotMarkWatchedWhenStoppedAboveWatchedThresholdByAnotherProfile() {
      var session = addSession();
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

      var sessionId = session.getSessionId();
      var otherProfileId = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  service.reportStreamSessionTimeline(
                      otherProfileId, sessionId, 6840, PlaybackState.STOPPED))
          .isInstanceOf(SessionNotFoundException.class);

      assertThat(sessionProgressRepository.findBySessionId(session.getSessionId())).isPresent();
      assertThat(watchHistoryRepository.count()).isZero();
      assertThat(eventPublisher.getEventsOfType(ItemWatchedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("Should treat session without owner profile as not found")
    void shouldTreatSessionWithoutOwnerProfileAsNotFound() {
      var session = StreamSessionFixture.buildMpegtsSessionOwnedBy(null);
      sessionRepository.save(session);
      saveMediaFileForSession(session);

      var sessionId = session.getSessionId();
      assertThatThrownBy(
              () ->
                  service.reportStreamSessionTimeline(
                      PROFILE_ID, sessionId, 6840, PlaybackState.STOPPED))
          .isInstanceOf(SessionNotFoundException.class);

      assertThat(sessionProgressRepository.count()).isZero();
      assertThat(watchHistoryRepository.count()).isZero();
      assertThat(eventPublisher.getEventsOfType(ItemWatchedEvent.class)).isEmpty();
    }

    @Test
    @DisplayName("Should not persist progress when position seconds is negative")
    void shouldNotPersistProgressWhenPositionSecondsIsNegative() {
      var session = addSession();

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), -100, PlaybackState.PLAYING);

      assertThat(sessionProgressRepository.count()).isZero();
    }

    @Test
    @DisplayName("Should not delete existing progress when stopped with negative position")
    void shouldNotDeleteExistingProgressWhenStoppedWithNegativePosition() {
      var session = addSession();
      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, session.getMediaFileId())
              .sessionId(session.getSessionId())
              .positionSeconds(3600)
              .build());

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), -100, PlaybackState.STOPPED);

      assertThat(sessionProgressRepository.findBySessionId(session.getSessionId())).isPresent();
    }
  }

  @Nested
  @DisplayName("Stopped Threshold Logic")
  class StoppedThresholdLogic {

    @Test
    @DisplayName("Should delete progress when stopped below min threshold")
    void shouldDeleteProgressWhenStoppedBelowMinThreshold() {
      var session = addSession(); // 7200s duration
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);
      assertThat(sessionProgressRepository.count()).isEqualTo(1);

      // Stop at 2% (144s / 7200s) — below 5% threshold
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 144, PlaybackState.STOPPED);

      assertThat(sessionProgressRepository.count()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stopThresholdCases")
    @DisplayName("Should apply stop thresholds when playback stopped")
    void shouldApplyStopThresholdsWhenPlaybackStopped(
        String description, int positionSeconds, boolean expectPersisted) {
      var session = addSession(); // 7200s duration

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), positionSeconds, PlaybackState.STOPPED);

      var progress = sessionProgressRepository.findBySessionId(session.getSessionId());
      if (expectPersisted) {
        assertThat(progress).isPresent();
        assertThat(progress.get().getPositionSeconds()).isEqualTo(positionSeconds);
      } else {
        assertThat(progress).isEmpty();
      }
    }

    static Stream<Arguments> stopThresholdCases() {
      return Stream.of(
          Arguments.of("above max percent marks watched (93%)", 6696, false),
          Arguments.of("exact max percent marks watched (90%, prod uses '>=')", 6480, false),
          Arguments.of("exact min percent persists (5%, prod uses '<')", 360, true),
          Arguments.of("between thresholds persists (50%)", 3600, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("markWatchedThresholdCases")
    @DisplayName("Should delete session progress when stopped and watched threshold is met")
    void shouldDeleteSessionProgressWhenWatchedThresholdMet(
        String description, int durationSeconds, int positionSeconds) {
      var session =
          StreamSessionFixture.sessionWithDurationBuilder(durationSeconds)
              .profileId(PROFILE_ID)
              .build();
      sessionRepository.save(session);
      saveMediaFileForSession(session);

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), positionSeconds, PlaybackState.STOPPED);

      assertThat(sessionProgressRepository.findBySessionId(session.getSessionId())).isEmpty();
    }

    static Stream<Arguments> markWatchedThresholdCases() {
      return Stream.of(
          Arguments.of("remaining time below max remaining seconds", 2400, 2150),
          Arguments.of("exact max remaining seconds boundary", 2400, 2100),
          Arguments.of("short content above max resume percent", 120, 110));
    }

    @ParameterizedTest
    @EnumSource(
        value = PlaybackState.class,
        names = {"PLAYING", "PAUSED"})
    @DisplayName("Should not apply thresholds when not stopped")
    void shouldNotApplyThresholdsWhenNotStopped(PlaybackState state) {
      var session = addSession(); // 7200s duration

      // Report at 95% — thresholds only apply on STOPPED
      service.reportStreamSessionTimeline(PROFILE_ID, session.getSessionId(), 6840, state);

      var progress =
          sessionProgressRepository.findBySessionId(session.getSessionId()).orElseThrow();
      assertThat(progress.getPositionSeconds()).isEqualTo(6840);
    }

    @Test
    @DisplayName(
        "Should not mark short content as watched via remaining seconds threshold when duration is below max remaining")
    void
        shouldNotMarkShortContentAsWatchedViaRemainingSecondsThresholdWhenDurationIsBelowMaxRemaining() {
      var shortSession =
          StreamSessionFixture.sessionWithDurationBuilder(120) // 2 min trailer
              .profileId(PROFILE_ID)
              .build();
      sessionRepository.save(shortSession);
      saveMediaFileForSession(shortSession);

      // Stop at 8.3% (10s / 120s) — above 5% min, 110s remaining < 300s maxRemaining
      // Without the duration guard this would incorrectly trigger MARK_WATCHED
      service.reportStreamSessionTimeline(
          PROFILE_ID, shortSession.getSessionId(), 10, PlaybackState.STOPPED);

      var progress =
          sessionProgressRepository.findBySessionId(shortSession.getSessionId()).orElseThrow();
      assertThat(progress.getPositionSeconds()).isEqualTo(10);
    }
  }

  @Nested
  @DisplayName("Watch Progress Events")
  class WatchProgressEvents {

    @Test
    @DisplayName("Should publish SessionProgressChangedEvent when stopped")
    void shouldPublishSessionProgressChangedEventWhenStopped() {
      var session = addSession();

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 3600, PlaybackState.STOPPED);

      var events = eventPublisher.getEventsOfType(SessionProgressChangedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().mediaFileId()).isEqualTo(session.getMediaFileId());
      assertThat(events.getFirst().positionSeconds()).isEqualTo(3600);
      assertThat(events.getFirst().state()).isEqualTo(PlaybackState.STOPPED);
    }

    @Test
    @DisplayName("Should publish SessionProgressChangedEvent when playing")
    void shouldPublishSessionProgressChangedEventWhenPlaying() {
      var session = addSession();

      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

      var events = eventPublisher.getEventsOfType(SessionProgressChangedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().positionSeconds()).isEqualTo(3600);
      assertThat(events.getFirst().state()).isEqualTo(PlaybackState.PLAYING);
    }

    @Test
    @DisplayName("Should publish ItemWatchedEvent when marked watched")
    void shouldPublishItemWatchedEventWhenMarkedWatched() {
      var session = addSession();
      var mediaFile = mediaFileRepository.findById(session.getMediaFileId()).orElseThrow();

      // Stop at 95% to trigger watched
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), (int) (7200 * 0.95), PlaybackState.STOPPED);

      var events = eventPublisher.getEventsOfType(ItemWatchedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().collectableId()).isEqualTo(mediaFile.getMediaId());
      assertThat(events.getFirst().sessionId()).isEqualTo(session.getSessionId());
      assertThat(events.getFirst().mediaFileId()).isEqualTo(session.getMediaFileId());
    }

    @Test
    @DisplayName("Should publish WatchStatusChangedEvent when marked watched")
    void shouldPublishWatchStatusChangedEventWhenMarkedWatched() {
      var session = addSession();
      var mediaFile = mediaFileRepository.findById(session.getMediaFileId()).orElseThrow();

      // Stop at 95% to trigger watched
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), (int) (7200 * 0.95), PlaybackState.STOPPED);

      var events = eventPublisher.getEventsOfType(WatchStatusChangedEvent.class);
      assertThat(events).hasSize(1);
      assertThat(events.getFirst().collectableId()).isEqualTo(mediaFile.getMediaId());
    }

    @Test
    @DisplayName("Should not publish events when progress discarded below threshold")
    void shouldNotPublishEventsWhenProgressDiscardedBelowThreshold() {
      var session = addSession();

      // Stop at 1% — below min threshold, deletes progress, no state change for other UIs
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 72, PlaybackState.STOPPED);

      assertThat(eventPublisher.getEventsOfType(SessionProgressChangedEvent.class)).isEmpty();
      assertThat(eventPublisher.getEventsOfType(ItemWatchedEvent.class)).isEmpty();
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
      service.reportStreamSessionTimeline(
          PROFILE_ID, session.getSessionId(), 3600, PlaybackState.PLAYING);

      // Session destroyed without STOPPED report (client crash)
      sessionRepository.removeById(session.getSessionId());

      // Progress should still be in DB
      var progress =
          sessionProgressRepository.findBySessionId(session.getSessionId()).orElseThrow();
      assertThat(progress.getPositionSeconds()).isEqualTo(3600);
    }
  }

  @Nested
  @DisplayName("Watch Progress Reset")
  class WatchProgressReset {

    @Test
    @DisplayName("Should delete all progress when resetting movie")
    void shouldDeleteAllProgressWhenResettingMovie() {
      var movieId = UUID.randomUUID();
      mediaFileRepository.save(buildMatchedMediaFile(movieId));
      var mediaFile = mediaFileRepository.findByMediaId(movieId).getFirst();

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mediaFile.getId()).positionSeconds(3600).build());
      assertThat(sessionProgressRepository.count()).isEqualTo(1);

      watchStatusService.markUnwatched(PROFILE_ID, movieId);

      assertThat(sessionProgressRepository.count()).isZero();
    }

    @Test
    @DisplayName("Should delete all episode progress when resetting season")
    void shouldDeleteAllEpisodeProgressWhenResettingSeason() {
      var season = seasonRepository.save(Season.builder().seasonNumber(1).build());
      var ep1 = episodeRepository.save(Episode.builder().episodeNumber(1).season(season).build());
      var ep2 = episodeRepository.save(Episode.builder().episodeNumber(2).season(season).build());

      var mf1 = mediaFileRepository.save(buildMatchedMediaFile(ep1.getId()));
      var mf2 = mediaFileRepository.save(buildMatchedMediaFile(ep2.getId()));

      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf1.getId()).positionSeconds(300).build());
      sessionProgressRepository.save(
          progressBuilder(PROFILE_ID, mf2.getId()).positionSeconds(600).build());
      assertThat(sessionProgressRepository.count()).isEqualTo(2);

      watchStatusService.markUnwatched(PROFILE_ID, season.getId());

      assertThat(sessionProgressRepository.count()).isZero();
    }

    @Test
    @DisplayName("Should delete all episode progress when resetting series")
    void shouldDeleteAllEpisodeProgressWhenResettingSeries() {
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
      assertThat(sessionProgressRepository.count()).isEqualTo(2);

      watchStatusService.markUnwatched(PROFILE_ID, series.getId());

      assertThat(sessionProgressRepository.count()).isZero();
    }
  }

  @Nested
  @DisplayName("Session Progress Persistence")
  class SessionProgressPersistence {

    private SessionPair reportTwoSessionsOnSameMediaFile() {
      var first = addSession();
      var second = addSessionForMediaFile(first.getMediaFileId());

      service.reportStreamSessionTimeline(
          PROFILE_ID, first.getSessionId(), 300, PlaybackState.PLAYING);
      service.reportStreamSessionTimeline(
          PROFILE_ID, second.getSessionId(), 600, PlaybackState.PLAYING);
      return new SessionPair(first, second);
    }

    private record SessionPair(StreamSession first, StreamSession second) {}

    @Test
    @DisplayName("Should persist separate rows for two sessions on same media file")
    void shouldPersistSeparateRowsForTwoSessionsOnSameMediaFile() {
      var sessions = reportTwoSessionsOnSameMediaFile();

      assertThat(sessionProgressRepository.count()).isEqualTo(2);

      var sp1 =
          sessionProgressRepository.findBySessionId(sessions.first().getSessionId()).orElseThrow();
      var sp2 =
          sessionProgressRepository.findBySessionId(sessions.second().getSessionId()).orElseThrow();
      assertThat(sp1.getPositionSeconds()).isEqualTo(300);
      assertThat(sp2.getPositionSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("Should return most recent session progress for resume")
    void shouldReturnMostRecentSessionProgressForResume() {
      var sessions = reportTwoSessionsOnSameMediaFile();

      var resume =
          sessionProgressRepository.findMostRecentByProfileIdAndMediaFileId(
              PROFILE_ID, sessions.first().getMediaFileId());

      assertThat(resume).isPresent();
      assertThat(resume.get().getPositionSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("Should delete only discarded session progress")
    void shouldDeleteOnlyDiscardedSessionProgress() {
      var sessions = reportTwoSessionsOnSameMediaFile();

      // Stop first session below min threshold (< 5% of 7200s = 360s) → DISCARD
      service.reportStreamSessionTimeline(
          PROFILE_ID, sessions.first().getSessionId(), 100, PlaybackState.STOPPED);

      assertThat(sessionProgressRepository.count()).isEqualTo(1);
      assertThat(sessionProgressRepository.findBySessionId(sessions.second().getSessionId()))
          .isPresent();
      assertThat(sessionProgressRepository.findBySessionId(sessions.first().getSessionId()))
          .isEmpty();
    }
  }

  @Nested
  @DisplayName("Re-watch Flows")
  class ReWatchFlows {

    @Test
    @DisplayName("Should accumulate watch history on re-watch completion")
    void shouldAccumulateWatchHistoryOnReWatchCompletion() {
      var session1 = addSession();
      markAsWatched(session1);

      assertThat(watchHistoryRepository.count()).isEqualTo(1);

      var session2 = addSessionForMediaFile(session1.getMediaFileId());
      markAsWatched(session2);

      assertThat(watchHistoryRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should dismiss history on mark unwatched without deleting rows")
    void shouldDismissHistoryOnMarkUnwatchedWithoutDeletingRows() {
      var session = addSession();
      markAsWatched(session);

      var collectableId =
          mediaFileRepository.findById(session.getMediaFileId()).orElseThrow().getMediaId();
      watchStatusService.markUnwatched(PROFILE_ID, collectableId);

      assertThat(watchHistoryRepository.count()).isEqualTo(1);
      var history = watchHistoryRepository.findAll().getFirst();
      assertThat(history.getDismissedAt()).isNotNull();
    }

    @Test
    @DisplayName("Should show watched after re-watch completion following dismiss")
    void shouldShowWatchedAfterReWatchCompletionFollowingDismiss() {
      var session1 = addSession();
      markAsWatched(session1);

      var collectableId =
          mediaFileRepository.findById(session1.getMediaFileId()).orElseThrow().getMediaId();
      watchStatusService.markUnwatched(PROFILE_ID, collectableId);

      var session2 = addSessionForMediaFile(session1.getMediaFileId());
      markAsWatched(session2);

      var latest =
          watchHistoryRepository.findFirstByProfileIdAndCollectableIdOrderByWatchedAtDesc(
              PROFILE_ID, collectableId);
      assertThat(latest).isPresent();
      assertThat(latest.get().getDismissedAt()).isNull();
    }
  }

  private StreamSession addSessionForMediaFile(UUID mediaFileId) {
    var session =
        StreamSessionFixture.defaultSessionBuilder()
            .mediaFileId(mediaFileId)
            .profileId(PROFILE_ID)
            .build();
    sessionRepository.save(session);
    return session;
  }
}
