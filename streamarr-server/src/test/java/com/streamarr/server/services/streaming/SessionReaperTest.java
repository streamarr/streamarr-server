package com.streamarr.server.services.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakePlaybackTranscodeJobService;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.transcode.engine.model.TranscodeJobState;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("UnitTest")
@DisplayName("Session Reaper Tests")
class SessionReaperTest {

  private FakePlaybackTranscodeJobService transcodeJobs;
  private InMemoryStreamingService streamingService;
  private TranscodeCapacityTracker transcodeCapacityTracker;
  private StreamingProperties properties;
  private SessionReaper reaper;

  @BeforeEach
  void setUp() {
    transcodeJobs = new FakePlaybackTranscodeJobService();
    streamingService = new InMemoryStreamingService();
    transcodeCapacityTracker = new TranscodeCapacityTracker();
    properties =
        StreamingProperties.builder()
            .segmentDuration(Duration.ofSeconds(6))
            .sessionTimeout(Duration.ofSeconds(60))
            .sessionRetention(Duration.ofHours(24))
            .build();
    reaper =
        new SessionReaper(streamingService, transcodeJobs, properties, transcodeCapacityTracker);
  }

  @Test
  @DisplayName("Should suspend an idle running whole job without removing its session")
  void shouldSuspendIdleRunningWholeJobWithoutRemovingItsSession() {
    var session = idleSession();
    transcodeJobs.observe(session.getSessionId(), TranscodeJobState.RUNNING, 0);

    reaper.reapSessions();

    assertThat(transcodeJobs.suspensionAttempts()).containsExactly(session.getSessionId());
    assertThat(streamingService.accessSession(session.getSessionId())).contains(session);
  }

  @Test
  @DisplayName("Should suspend an idle admitting whole job")
  void shouldSuspendIdleAdmittingWholeJob() {
    var session = idleSession();
    transcodeJobs.observe(session.getSessionId(), TranscodeJobState.ADMITTING, 0);

    reaper.reapSessions();

    assertThat(transcodeJobs.suspensionAttempts()).containsExactly(session.getSessionId());
  }

  @Test
  @DisplayName("Should retain an idle session when suspension cleanup remains pending")
  void shouldRetainIdleSessionWhenSuspensionCleanupRemainsPending() {
    var session = idleSession();
    transcodeJobs.observe(session.getSessionId(), TranscodeJobState.RUNNING, 0);
    transcodeJobs.returnSuspensionCleanup(RuntimeTranscodeCleanup.PENDING);
    var logger = (Logger) LoggerFactory.getLogger(SessionReaper.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      reaper.reapSessions();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(transcodeJobs.suspensionAttempts()).containsExactly(session.getSessionId());
    assertThat(streamingService.accessSession(session.getSessionId())).contains(session);
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains(session.getSessionId().toString()));
  }

  @Test
  @DisplayName("Should not suspend a recently accessed running whole job")
  void shouldNotSuspendRecentlyAccessedRunningWholeJob() {
    var session = addSession(Instant.now().minusSeconds(10));
    transcodeJobs.observe(session.getSessionId(), TranscodeJobState.RUNNING, 0);

    reaper.reapSessions();

    assertThat(transcodeJobs.suspensionAttempts()).isEmpty();
  }

  @Test
  @DisplayName("Should treat a completed whole job as terminal output rather than a dead process")
  void shouldTreatCompletedWholeJobAsTerminalOutputRatherThanDeadProcess() {
    var session = idleSession();
    transcodeJobs.observe(session.getSessionId(), TranscodeJobState.COMPLETED, 0);

    reaper.reapSessions();

    assertThat(transcodeJobs.suspensionAttempts()).isEmpty();
  }

  @Test
  @DisplayName("Should not release a restarted claim from a stale completed observation")
  void shouldNotReleaseRestartedClaimFromStaleCompletedObservation() throws Exception {
    var session = addSession(Instant.now().minusSeconds(10));
    var blockingJobs = new BlockingInspectionPlaybackTranscodeJobService();
    blockingJobs.observe(session.getSessionId(), TranscodeJobState.COMPLETED, 0);
    assertThat(transcodeCapacityTracker.claimExact(session.getSessionId(), 1, 1)).isTrue();
    transcodeCapacityTracker.markActive(session.getSessionId());
    var raceReaper =
        new SessionReaper(streamingService, blockingJobs, properties, transcodeCapacityTracker);

    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var reap = executor.submit(raceReaper::reapSessions);
      assertThat(blockingJobs.awaitInspection()).isTrue();
      assertThat(transcodeCapacityTracker.claimExact(session.getSessionId(), 1, 1)).isTrue();
      transcodeCapacityTracker.markActive(session.getSessionId());
      blockingJobs.releaseInspection();
      reap.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    assertThat(transcodeCapacityTracker.claimExact(UUID.randomUUID(), 1, 1)).isFalse();
  }

  @Test
  @DisplayName("Should not release a restarted claim after idle suspension completes")
  void shouldNotReleaseRestartedClaimAfterIdleSuspensionCompletes() throws Exception {
    var session = idleSession();
    var blockingJobs = new BlockingSuspensionPlaybackTranscodeJobService();
    blockingJobs.observe(session.getSessionId(), TranscodeJobState.RUNNING, 0);
    assertThat(transcodeCapacityTracker.claimExact(session.getSessionId(), 1, 1)).isTrue();
    transcodeCapacityTracker.markActive(session.getSessionId());
    var raceReaper =
        new SessionReaper(streamingService, blockingJobs, properties, transcodeCapacityTracker);

    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var reap = executor.submit(raceReaper::reapSessions);
      assertThat(blockingJobs.awaitSuspension()).isTrue();
      assertThat(transcodeCapacityTracker.claimExact(session.getSessionId(), 1, 1)).isTrue();
      blockingJobs.releaseSuspension();
      reap.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    transcodeCapacityTracker.markActive(session.getSessionId());

    assertThat(transcodeCapacityTracker.claimExact(UUID.randomUUID(), 1, 1)).isFalse();
  }

  @Test
  @DisplayName("Should report a failed whole job without discarding replacement authority")
  void shouldReportFailedWholeJobWithoutDiscardingReplacementAuthority() {
    var session = addSession(Instant.now().minusSeconds(10));
    transcodeJobs.observe(session.getSessionId(), TranscodeJobState.FAILED, 0);
    var logger = (Logger) LoggerFactory.getLogger(SessionReaper.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);

    try {
      reaper.reapSessions();
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(transcodeJobs.suspensionAttempts()).isEmpty();
    assertThat(appender.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains(session.getSessionId().toString()));
  }

  @Test
  @DisplayName("Should fail closed when active whole-job inspection is unavailable")
  void shouldFailClosedWhenActiveWholeJobInspectionIsUnavailable() {
    var session = idleSession();
    transcodeJobs.observe(session.getSessionId(), TranscodeJobState.RUNNING, 0);
    transcodeJobs.makeInspectionUnavailable(session.getSessionId());

    reaper.reapSessions();

    assertThat(transcodeJobs.suspensionAttempts()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore a session with no active whole job")
  void shouldIgnoreSessionWithNoActiveWholeJob() {
    idleSession();

    reaper.reapSessions();

    assertThat(transcodeJobs.suspensionAttempts()).isEmpty();
  }

  private StreamSession idleSession() {
    return addSession(Instant.now().minusSeconds(120));
  }

  private StreamSession addSession(Instant lastAccessedAt) {
    var session = StreamSessionFixture.buildMpegtsSession();
    session.setLastAccessedAt(lastAccessedAt);
    streamingService.addSession(session);
    return session;
  }

  private static final class InMemoryStreamingService implements StreamingService {

    private final ConcurrentHashMap<UUID, StreamSession> sessions = new ConcurrentHashMap<>();

    private void addSession(StreamSession session) {
      sessions.put(session.getSessionId(), session);
    }

    @Override
    public StreamSession createSession(CreateRuntimeStreamSessionCommand command) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<StreamSession> accessSession(UUID sessionId) {
      return Optional.ofNullable(sessions.get(sessionId));
    }

    @Override
    public void destroySession(UUID sessionId) {
      sessions.remove(sessionId);
    }

    @Override
    public void destroySession(UUID sessionId, UUID profileId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Collection<StreamSession> getAllSessions() {
      return Collections.unmodifiableCollection(sessions.values());
    }

    @Override
    public int getActiveSessionCount() {
      return sessions.size();
    }

    @Override
    public void resumeSessionIfNeeded(UUID sessionId, String segmentName) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class BlockingInspectionPlaybackTranscodeJobService
      extends FakePlaybackTranscodeJobService {

    private final java.util.concurrent.CountDownLatch inspectionCaptured =
        new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch allowInspection =
        new java.util.concurrent.CountDownLatch(1);

    @Override
    public ActiveTranscodeJobInspection inspectActive(UUID sessionId) {
      var inspection = super.inspectActive(sessionId);
      inspectionCaptured.countDown();
      await(allowInspection);
      return inspection;
    }

    private boolean awaitInspection() throws InterruptedException {
      return inspectionCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void releaseInspection() {
      allowInspection.countDown();
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
      try {
        latch.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(exception);
      }
    }
  }

  private static final class BlockingSuspensionPlaybackTranscodeJobService
      extends FakePlaybackTranscodeJobService {

    private final java.util.concurrent.CountDownLatch suspensionCaptured =
        new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch allowSuspension =
        new java.util.concurrent.CountDownLatch(1);

    @Override
    public RuntimeTranscodeCleanup suspend(UUID sessionId) {
      var cleanup = super.suspend(sessionId);
      suspensionCaptured.countDown();
      BlockingInspectionPlaybackTranscodeJobService.await(allowSuspension);
      return cleanup;
    }

    private boolean awaitSuspension() throws InterruptedException {
      return suspensionCaptured.await(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void releaseSuspension() {
      allowSuspension.countDown();
    }
  }
}
