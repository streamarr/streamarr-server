package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.acceptedPlayback;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.playbackServiceBuilder;
import static com.streamarr.server.fixtures.PlaybackSessionFixture.startPlaybackBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.StreamingOptions;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCreatingStreamingService;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeServerSettingsRepository;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.PlaybackSessionFixture;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Session Reaper Tests")
class SessionReaperTest {

  // Starts at the wall clock, which new stream sessions stamp as their last access.
  private final MutableClock clock = new MutableClock(new AtomicReference<>(Instant.now()));
  private final FakeServerSettingsRepository serverSettingsRepository =
      new FakeServerSettingsRepository();
  private final ServerSettingsService serverSettings =
      new ServerSettingsService(
          new FakeAuthorizationService(accountScopedBuilder().build()), serverSettingsRepository);
  private FakeTranscodeExecutor executor;
  private FakeCreatingStreamingService streamingService;
  private PlaybackSessionService playback;
  private ArrayDeque<Runnable> work;
  private StreamingProperties properties;
  private ProducerLifecycleService producerLifecycle;
  private SessionReaper reaper;

  @BeforeEach
  void setUp() {
    executor = new FakeTranscodeExecutor();
    var runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    streamingService = new FakeCreatingStreamingService(runtimeRegistry);
    work = new ArrayDeque<>();
    playback = playbackServiceBuilder(streamingService).executor(work::add).build();
    properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .build();
    producerLifecycle =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(executor)
            .segmentStore(new FakeSegmentStore())
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .build()
            .lifecycle();
    reaper = reaperBuilder().build();
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should finish reaping child sessions when closing logical playback throws")
  void shouldFinishReapingChildSessionsWhenClosingLogicalPlaybackThrows(
      boolean logicalActivityExpired) {
    var now = clock.instant();
    var logicalActivity = logicalActivityExpired ? now.minus(Duration.ofHours(25)) : now;
    playback =
        playbackServiceBuilder(streamingService)
            .clock(Clock.fixed(logicalActivity, ZoneOffset.UTC))
            .build();
    var command = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(command));
    streamingService
        .getAllSessions()
        .iterator()
        .next()
        .setLastAccessedAt(now.minus(Duration.ofHours(25)));
    streamingService.addSession(buildSession(now.minus(Duration.ofHours(25))));
    streamingService.failNextDestroy(initial.currentFormatAttempt().streamSessionId());
    var cleanup = reaperBuilder().build();

    assertThatNoException().isThrownBy(cleanup::reapSessions);

    assertThat(playback.access(PlaybackSessionFixture.playbackRequest(initial, command))).isEmpty();
    assertThat(streamingService.getAllSessions()).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = VideoQuality.class,
      names = {"AUTO", "ORIGINAL"})
  @DisplayName(
      "Should reap expired logical playback when its pending or terminal attempt has no child")
  void shouldReapExpiredLogicalPlaybackWhenItsPendingOrTerminalAttemptHasNoChild(
      VideoQuality quality) {
    playback =
        playbackServiceBuilder(streamingService)
            .executor(work::add)
            .clock(Clock.fixed(clock.instant().minus(Duration.ofHours(25)), ZoneOffset.UTC))
            .build();
    var command =
        startPlaybackBuilder().options(StreamingOptions.builder().quality(quality).build()).build();
    var initial = acceptedPlayback(playback.start(command));
    var unrelated = startPlaybackBuilder().build();
    var retained = acceptedPlayback(playback.start(unrelated));
    var childId = initial.currentFormatAttempt().streamSessionId();
    playback.onVideoCopyOutputMismatch(
        new VideoCopyOutputMismatchEvent(childId, UUID.randomUUID(), "default"));
    streamingService.destroySession(childId);
    assertThat(
            playback
                .access(PlaybackSessionFixture.playbackRequest(initial, command))
                .orElseThrow()
                .currentFormatAttempt())
        .isNotInstanceOf(PlaybackFormatAttempt.Ready.class);

    reaperBuilder().build().reapSessions();
    work.forEach(Runnable::run);

    assertThat(playback.access(PlaybackSessionFixture.playbackRequest(initial, command))).isEmpty();
    assertThat(playback.access(PlaybackSessionFixture.playbackRequest(retained, unrelated)))
        .contains(retained);
    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactly(retained.currentFormatAttempt().streamSessionId());
  }

  @Test
  @DisplayName("Should preserve replacement playback when the expired child snapshot is stale")
  void shouldPreserveReplacementPlaybackWhenTheExpiredChildSnapshotIsStale() {
    playback = playbackServiceBuilder(streamingService).build();
    var command = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(command));
    var oldChildId = initial.currentFormatAttempt().streamSessionId();
    streamingService
        .getAllSessions()
        .iterator()
        .next()
        .setLastAccessedAt(clock.instant().minus(Duration.ofHours(25)));
    streamingService.afterNextSnapshot(
        () ->
            playback.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(oldChildId, UUID.randomUUID(), "default")));

    reaperBuilder().build().reapSessions();

    var current =
        playback
            .access(PlaybackSessionFixture.playbackRequest(initial, command))
            .orElseThrow()
            .currentFormatAttempt();
    assertThat(current).isInstanceOf(PlaybackFormatAttempt.Ready.class);
    assertThat(current.streamSessionId()).isNotEqualTo(oldChildId);
    assertThat(streamingService.getAllSessions())
        .extracting(StreamSession::getSessionId)
        .containsExactly(current.streamSessionId());
  }

  @Test
  @DisplayName("Should close logical playback before an expired child reports a format mismatch")
  void shouldCloseLogicalPlaybackBeforeAnExpiredChildReportsAFormatMismatch() {
    var command = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(command));
    var childId = initial.currentFormatAttempt().streamSessionId();
    streamingService
        .getAllSessions()
        .iterator()
        .next()
        .setLastAccessedAt(clock.instant().minus(Duration.ofHours(25)));
    streamingService.beforeNextDestroy(
        () ->
            playback.onVideoCopyOutputMismatch(
                new VideoCopyOutputMismatchEvent(childId, UUID.randomUUID(), "default")));

    reaper.reapSessions();
    work.forEach(Runnable::run);

    assertThat(playback.access(PlaybackSessionFixture.playbackRequest(initial, command))).isEmpty();
    assertThat(streamingService.getAllSessions()).isEmpty();
  }

  @Test
  @DisplayName("Should mark handle as suspended when session is idle past timeout")
  void shouldMarkHandleAsSuspendedWhenIdlePastTimeout() {
    var session = buildSession(clock.instant().minusSeconds(120));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
  }

  @Test
  @DisplayName("Should preserve session when suspending idle session")
  void shouldPreserveSessionWhenSuspendingIdleSession() {
    var session = buildSession(clock.instant().minusSeconds(120));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.accessSession(playbackRequest(session))).isPresent();
  }

  @Test
  @DisplayName("Should destroy session when idle past retention timeout")
  void shouldDestroySessionWhenIdlePastRetentionTimeout() {
    var session = buildSession(clock.instant().minusSeconds(90_000));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.accessSession(playbackRequest(session))).isEmpty();
  }

  @Test
  @DisplayName("Should end a direct-play session when the retention passes without requests")
  void shouldEndDirectPlaySessionWhenRetentionPassesWithoutRequests() {
    var expired = StreamSessionFixture.directPlaySessionBuilder().build();
    expired.setLastAccessedAt(clock.instant().minus(Duration.ofHours(1)).minusSeconds(1));
    var recent = StreamSessionFixture.directPlaySessionBuilder().build();
    recent.setLastAccessedAt(clock.instant().minus(Duration.ofHours(1)).plusSeconds(60));
    streamingService.addSession(expired);
    streamingService.addSession(recent);

    reaper.reapSessions();

    assertThat(streamingService.getAllSessions()).containsExactly(recent);
  }

  @Test
  @DisplayName(
      "Should destroy a session idle past one hour when no ServerAdmin has changed the retention")
  void shouldDestroySessionIdlePastOneHourWhenNoServerAdminHasChangedRetention() {
    var session = buildSession(clock.instant());
    streamingService.addSession(session);
    clock.advance(Duration.ofMinutes(61));

    reaper.reapSessions();

    assertThat(streamingService.accessSession(playbackRequest(session))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should close logical playback idle past one hour when its stream session is already gone")
  void shouldCloseLogicalPlaybackIdlePastOneHourWhenItsStreamSessionIsAlreadyGone() {
    playback = playbackServiceBuilder(streamingService).clock(clock).build();
    var command = startPlaybackBuilder().build();
    var initial = acceptedPlayback(playback.start(command));
    streamingService.destroySession(initial.currentFormatAttempt().streamSessionId());
    clock.advance(Duration.ofMinutes(61));

    reaperBuilder().build().reapSessions();

    assertThat(playback.access(PlaybackSessionFixture.playbackRequest(initial, command))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep an idle session until the stored retention passes when it is three hours")
  void shouldKeepIdleSessionUntilStoredRetentionPassesWhenItIsThreeHours() {
    serverSettingsRepository.findAll().getFirst().setIdleSegmentRetentionMinutes(180);
    var session = buildSession(clock.instant());
    streamingService.addSession(session);
    clock.advance(Duration.ofHours(2));

    reaper.reapSessions();

    assertThat(streamingService.accessSession(playbackRequest(session))).isPresent();

    clock.advance(Duration.ofMinutes(61));
    reaper.reapSessions();

    assertThat(streamingService.accessSession(playbackRequest(session))).isEmpty();
  }

  @Test
  @DisplayName("Should not suspend session when already suspended")
  void shouldNotSuspendSessionWhenAlreadySuspended() {
    var session = buildSession(clock.instant().minusSeconds(120));
    session.setHandle(StreamSessionFixture.mintHandle(1L, TranscodeStatus.SUSPENDED));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.SUSPENDED);
    assertThat(streamingService.accessSession(playbackRequest(session))).isPresent();
  }

  @Test
  @DisplayName("Should not suspend session when handles are failed")
  void shouldNotSuspendSessionWhenHandlesAreFailed() {
    var session = buildSession(clock.instant().minusSeconds(120));
    session.setHandle(StreamSessionFixture.mintHandle(1L, TranscodeStatus.FAILED));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.FAILED);
    assertThat(streamingService.accessSession(playbackRequest(session))).isPresent();
  }

  @Test
  @DisplayName("Should stop executor when suspending idle session")
  void shouldStopExecutorWhenSuspendingIdleSession() {
    var session = buildSession(clock.instant().minusSeconds(120));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(executor.getStopped()).contains(session.getSessionId());
  }

  @Test
  @DisplayName("Should preserve session when recently accessed")
  void shouldPreserveSessionWhenRecentlyAccessed() {
    var session = buildSession(clock.instant().minusSeconds(10));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(streamingService.accessSession(playbackRequest(session))).isPresent();
  }

  @Test
  @DisplayName("Should leave dead producer handles untouched when session is recently accessed")
  void shouldLeaveDeadProducerHandlesUntouchedWhenSessionIsRecentlyAccessed() {
    var session = buildSession(clock.instant().minusSeconds(10));
    session.setHandle(StreamSessionFixture.mintHandle(1234L, TranscodeStatus.ACTIVE));
    streamingService.addSession(session);
    executor.markDead(session.getSessionId());

    reaper.reapSessions();

    // Dead-producer detection and recovery happen at request time, never on the reaper's timer.
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getHandle().orElseThrow().processId()).hasValue(1234L);
  }

  @Test
  @DisplayName("Should not change handles when all variants are running")
  void shouldNotChangeHandlesWhenAllVariantsRunning() {
    var session = buildAbrSession(clock.instant().minusSeconds(10));
    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getVariantHandle("1080p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.ACTIVE);
    assertThat(session.getVariantHandle("720p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should skip already failed handles when suspending idle session")
  void shouldSkipAlreadyFailedHandlesWhenSuspendingIdleSession() {
    var session = buildSession(clock.instant().minusSeconds(120));
    session.setVariantHandle(
        "1080p", StreamSessionFixture.mintHandle(100L, TranscodeStatus.ACTIVE));
    session.setVariantHandle("720p", StreamSessionFixture.mintHandle(101L, TranscodeStatus.FAILED));

    executor.start(
        TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(0)
            .targetSegmentDuration(6)
            .framerate(OptionalDouble.of(24.0))
            .transcodeDecision(session.getTranscodeDecision())
            .width(1920)
            .height(1080)
            .bitrate(5_000_000)
            .variantLabel("1080p")
            .encoderPreset(EncoderPreset.FASTER)
            .build());

    streamingService.addSession(session);

    reaper.reapSessions();

    assertThat(session.getVariantHandle("1080p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.SUSPENDED);
    assertThat(session.getVariantHandle("720p").orElseThrow().status())
        .isEqualTo(TranscodeStatus.FAILED);
  }

  @Test
  @DisplayName("Should keep reaping remaining sessions when one session fails to suspend")
  void shouldKeepReapingRemainingSessionsWhenOneSessionFailsToSuspend() {
    var failing = buildSession(clock.instant().minusSeconds(120));
    var expired = buildSession(clock.instant().minusSeconds(90_000));
    streamingService.addSession(failing);
    streamingService.addSession(expired);
    executor.failOnStop(failing.getSessionId());

    assertThatNoException().isThrownBy(reaper::reapSessions);

    assertThat(streamingService.accessSession(playbackRequest(expired))).isEmpty();
  }

  private SessionReaper.SessionReaperBuilder reaperBuilder() {
    return SessionReaper.builder()
        .streamingService(streamingService)
        .properties(properties)
        .producerLifecycle(producerLifecycle)
        .playbackSessionService(playback)
        .serverSettings(serverSettings)
        .segmentFolderSpace(() -> 1.0)
        .meterRegistry(new SimpleMeterRegistry())
        .clock(clock);
  }

  private StreamSession buildSession(Instant lastAccessedAt) {
    var session = StreamSessionFixture.buildActiveSession();
    session.setLastAccessedAt(lastAccessedAt);
    return session;
  }

  private StreamSession buildAbrSession(Instant lastAccessedAt) {
    var session = StreamSessionFixture.buildActiveSession();
    session.setLastAccessedAt(lastAccessedAt);

    session.setVariantHandle(
        "1080p", StreamSessionFixture.mintHandle(100L, TranscodeStatus.ACTIVE));
    session.setVariantHandle("720p", StreamSessionFixture.mintHandle(101L, TranscodeStatus.ACTIVE));

    executor.start(
        TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(0)
            .targetSegmentDuration(6)
            .framerate(OptionalDouble.of(24.0))
            .transcodeDecision(session.getTranscodeDecision())
            .width(1920)
            .height(1080)
            .bitrate(5_000_000)
            .variantLabel("1080p")
            .encoderPreset(EncoderPreset.FASTER)
            .build());
    executor.start(
        TranscodeRequest.builder()
            .sessionId(session.getSessionId())
            .sourcePath(Path.of("/media/movie.mkv"))
            .seekPosition(0)
            .targetSegmentDuration(6)
            .framerate(OptionalDouble.of(24.0))
            .transcodeDecision(session.getTranscodeDecision())
            .width(1280)
            .height(720)
            .bitrate(3_000_000)
            .variantLabel("720p")
            .encoderPreset(EncoderPreset.FASTER)
            .build());

    return session;
  }
}
