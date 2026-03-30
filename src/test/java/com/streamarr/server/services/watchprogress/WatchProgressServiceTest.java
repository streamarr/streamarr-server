package com.streamarr.server.services.watchprogress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.streamarr.server.config.WatchProgressProperties;
import com.streamarr.server.domain.streaming.PlaybackState;
import com.streamarr.server.domain.streaming.StreamSession;
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
}
