package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultVariantBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.ServerSettingsFixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Producer Lifecycle Destroy Race Tests")
class ProducerLifecycleDestroyRaceTest {

  @Test
  @DisplayName("Should not leave a producer running when destroy interleaves initial startup")
  void shouldNotLeaveProducerRunningWhenDestroyInterleavesInitialStartup() throws Exception {
    var raceGate = new DestroyRaceGate();
    var transcodeExecutor = new StartGatingExecutor(raceGate);
    var runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var lifecycle = lifecycleWith(transcodeExecutor, raceGate.mutexFactory(), runtimeRegistry);
    var session =
        defaultSessionBuilder()
            .variants(
                List.of(
                    defaultVariantBuilder()
                        .width(1920)
                        .height(1080)
                        .videoBitrate(5_000_000L)
                        .label("1080p")
                        .build(),
                    defaultVariantBuilder()
                        .width(1280)
                        .height(720)
                        .videoBitrate(3_000_000L)
                        .label("720p")
                        .build()))
            .build();
    runtimeRegistry.save(session);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = executor.submit(() -> lifecycle.startFirstListedVariant(session));
      transcodeExecutor.awaitStart();

      var destroy =
          executor.submit(
              () -> raceGate.runDestroy(() -> lifecycle.stopForDestroy(session.getSessionId())));
      raceGate.awaitDestroyReady();

      transcodeExecutor.releaseStart();
      start.get(5, TimeUnit.SECONDS);
      destroy.get(5, TimeUnit.SECONDS);
    }

    assertThat(transcodeExecutor.isRunning(session.getSessionId(), "1080p"))
        .as("a destroyed session must not retain an orphaned producer")
        .isFalse();
  }

  private static ProducerLifecycleService lifecycleWith(
      FakeTranscodeExecutor transcodeExecutor,
      MutexFactory<UUID> sessionMutex,
      FakeRuntimeStreamSessionRegistry runtimeRegistry) {
    return ProducerLifecycleService.builder()
        .transcodeExecutor(transcodeExecutor)
        .segmentStore(new FakeSegmentStore())
        .properties(
            StreamingProperties.builder()
                .maxConcurrentTranscodes(3)
                .targetSegmentDuration(Duration.ofSeconds(6))
                .sessionTimeout(Duration.ofSeconds(60))
                .build())
        .runtimeRegistry(runtimeRegistry)
        .sessionMutex(sessionMutex)
        .serverSettings(ServerSettingsFixture.defaultServerSettings())
        .build();
  }

  private static final class StartGatingExecutor extends FakeTranscodeExecutor {

    private final DestroyRaceGate raceGate;
    private final CountDownLatch reachedStart = new CountDownLatch(1);
    private final CountDownLatch releaseStart = new CountDownLatch(1);
    private final AtomicInteger starts = new AtomicInteger();

    private StartGatingExecutor(DestroyRaceGate raceGate) {
      this.raceGate = raceGate;
    }

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      if (starts.incrementAndGet() == 1) {
        reachedStart.countDown();
        awaitRelease();
      }

      return super.start(request);
    }

    @Override
    public void stop(UUID sessionId) {
      super.stop(sessionId);
      raceGate.destroyStoppedProducers();
    }

    private void awaitStart() throws InterruptedException {
      assertThat(reachedStart.await(5, TimeUnit.SECONDS))
          .as("initial startup must reach the first-listed variant's start")
          .isTrue();
    }

    private void releaseStart() {
      releaseStart.countDown();
    }

    private void awaitRelease() {
      try {
        if (!releaseStart.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Start gate was never released");
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Signals when destroy has either stopped the old producers or queued behind startup. */
  private static final class DestroyRaceGate {

    private final CountDownLatch destroyReady = new CountDownLatch(1);
    private final DestroyAwareLock lock = new DestroyAwareLock();
    private volatile Thread destroyThread;

    private MutexFactory<UUID> mutexFactory() {
      return new MutexFactory<>() {
        @Override
        public ReentrantLock getMutex(UUID ignoredSessionId) {
          return lock;
        }
      };
    }

    private void runDestroy(Runnable destroy) {
      destroyThread = Thread.currentThread();
      destroy.run();
    }

    private void destroyStoppedProducers() {
      destroyReady.countDown();
    }

    private void awaitDestroyReady() throws InterruptedException {
      assertThat(destroyReady.await(5, TimeUnit.SECONDS))
          .as("destroy must stop existing producers or queue behind startup")
          .isTrue();
    }

    private final class DestroyAwareLock extends ReentrantLock {

      @Override
      public void lock() {
        if (Thread.currentThread() != destroyThread) {
          super.lock();
          return;
        }

        if (tryLock()) {
          return;
        }

        destroyReady.countDown();
        super.lock();
      }
    }
  }
}
