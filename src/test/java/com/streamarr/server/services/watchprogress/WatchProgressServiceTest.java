package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.WatchProgress;
import com.streamarr.server.exceptions.SessionNotFoundException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeStreamSessionRepository;
import com.streamarr.server.fakes.FakeWatchProgressRepository;
import com.streamarr.server.fixtures.StreamSessionFixture;
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
  private CapturingEventPublisher eventPublisher;
  private WatchProgressService service;

  private static final UUID USER_ID = UUID.randomUUID();

  @BeforeEach
  void setUp() {
    sessionRepository = new FakeStreamSessionRepository();
    watchProgressRepository = new FakeWatchProgressRepository();
    eventPublisher = new CapturingEventPublisher();
    var properties = new WatchProgressProperties(5.0, 90.0, 300);
    service =
        new WatchProgressService(
            sessionRepository, watchProgressRepository, properties, eventPublisher);
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
}
