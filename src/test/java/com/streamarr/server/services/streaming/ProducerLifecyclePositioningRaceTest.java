package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Producer Lifecycle Positioning Race Tests")
class ProducerLifecyclePositioningRaceTest {

  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private PositioningRaceGate raceGate;
  private ProducerLifecycleService lifecycle;

  @BeforeEach
  void setUp() {
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    raceGate = new PositioningRaceGate();
    lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .maxConcurrentTranscodes(3)
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .sessionTimeout(Duration.ofSeconds(60))
                    .build())
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(raceGate.mutexFactory())
            .build();
  }

  @Test
  @DisplayName(
      "Should not relocate a session when it is removed before acquiring the session mutex")
  void shouldNotRelocateSessionWhenItIsRemovedBeforeAcquiringSessionMutex() throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var positioning =
          executor.submit(
              () ->
                  raceGate.blockAtLock(
                      () -> lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts")));
      try {
        raceGate.awaitBlocked();
        assertThat(lifecycle.removeSession(session.getSessionId())).isTrue();
      } finally {
        raceGate.release();
      }
      positioning.get(5, TimeUnit.SECONDS);
    }

    assertThat(runtimeRegistry.findById(session.getSessionId())).isEmpty();
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  @Test
  @DisplayName(
      "Should not relocate a session when the requested segment is published before acquiring the session mutex")
  void shouldNotRelocateSessionWhenRequestedSegmentIsPublishedBeforeAcquiringSessionMutex()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var positioning =
          executor.submit(
              () ->
                  raceGate.blockAtLock(
                      () -> lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts")));
      try {
        raceGate.awaitBlocked();
        segmentStore.addSegment(session.getSessionId(), "segment100.ts", new byte[] {0x47});
      } finally {
        raceGate.release();
      }
      positioning.get(5, TimeUnit.SECONDS);
    }

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
    assertThat(segmentStore.readSegment(session.getSessionId(), "segment100.ts"))
        .containsExactly(0x47);
  }

  @Test
  @DisplayName(
      "Should not relocate a session when producer progress catches up before acquiring the session mutex")
  void shouldNotRelocateSessionWhenProducerProgressCatchesUpBeforeAcquiringSessionMutex()
      throws Exception {
    var session = startedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();
    var attemptBefore = session.getHandle().orElseThrow().attemptId();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var positioning =
          executor.submit(
              () ->
                  raceGate.blockAtLock(
                      () -> lifecycle.ensurePositioned(session.getSessionId(), "segment100.ts")));
      try {
        raceGate.awaitBlocked();
        segmentStore.addSegment(session.getSessionId(), "segment96.ts", new byte[] {0x47});
      } finally {
        raceGate.release();
      }
      positioning.get(5, TimeUnit.SECONDS);
    }

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
    assertThat(session.getHandle().orElseThrow().attemptId()).isEqualTo(attemptBefore);
  }

  @Test
  @DisplayName(
      "Should not resume a session twice when another caller resumes it before acquiring the session mutex")
  void shouldNotResumeSessionTwiceWhenAnotherCallerResumesItBeforeAcquiringSessionMutex()
      throws Exception {
    var session = suspendedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var laggingPositioning =
          executor.submit(
              () ->
                  raceGate.blockAtLock(
                      () -> lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts")));
      try {
        raceGate.awaitBlocked();
        lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts");
      } finally {
        raceGate.release();
      }
      laggingPositioning.get(5, TimeUnit.SECONDS);
    }

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore + 1);
    assertThat(session.getHandle().orElseThrow().status()).isEqualTo(TranscodeStatus.ACTIVE);
  }

  @Test
  @DisplayName("Should not resume a session when it is removed before acquiring the session mutex")
  void shouldNotResumeSessionWhenItIsRemovedBeforeAcquiringSessionMutex() throws Exception {
    var session = suspendedSession();
    var startsBefore = transcodeExecutor.getStartedRequests().size();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var positioning =
          executor.submit(
              () ->
                  raceGate.blockAtLock(
                      () -> lifecycle.ensurePositioned(session.getSessionId(), "segment5.ts")));
      try {
        raceGate.awaitBlocked();
        assertThat(lifecycle.removeSession(session.getSessionId())).isTrue();
      } finally {
        raceGate.release();
      }
      positioning.get(5, TimeUnit.SECONDS);
    }

    assertThat(runtimeRegistry.findById(session.getSessionId())).isEmpty();
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(startsBefore);
  }

  private StreamSession startedSession() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  private StreamSession suspendedSession() {
    var session = startedSession();
    session.setHandle(mintHandle(1L, TranscodeStatus.SUSPENDED));
    transcodeExecutor.markDead(session.getSessionId());
    return session;
  }

  private static final class PositioningRaceGate {

    private final CountDownLatch blocked = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private final ReentrantLock lock = new PositioningLock();
    private volatile Thread positioningThread;

    private MutexFactory<UUID> mutexFactory() {
      return new MutexFactory<>() {
        @Override
        public ReentrantLock getMutex(UUID sessionId) {
          return lock;
        }
      };
    }

    private void blockAtLock(Runnable positioning) {
      positioningThread = Thread.currentThread();
      positioning.run();
    }

    private void awaitBlocked() throws InterruptedException {
      assertThat(blocked.await(5, TimeUnit.SECONDS))
          .as("positioning must reach the session mutex")
          .isTrue();
    }

    private void release() {
      released.countDown();
    }

    private final class PositioningLock extends ReentrantLock {

      @Override
      public void lock() {
        if (Thread.currentThread() == positioningThread) {
          blocked.countDown();
          try {
            assertThat(released.await(5, TimeUnit.SECONDS))
                .as("the positioning mutex gate must be released")
                .isTrue();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting at the positioning mutex", e);
          }
        }
        super.lock();
      }
    }
  }
}
