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
import com.streamarr.server.services.concurrency.MutexFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PR #247 B10 RED validation. {@code startAll} deliberately takes no session mutex, justified by
 * "runs before the session is reachable" — but {@code HlsStreamingService.createSession} publishes
 * the session to the registry before calling it, so the reaper/shutdown destroy path can interleave
 * mid-start. The destroy stops everything started so far; the unlocked {@code startAll} then starts
 * the remaining variant, orphaning its producer (a real FFmpeg process in production) until JVM
 * shutdown. Move into the normal suite when the fix exists.
 */
@Tag("UnitTest")
@DisplayName("PR #247 B10 startAll destroy race RED validation")
class Pr247StartAllDestroyRaceRedTest {

  @Test
  @DisplayName("Should not leave a producer running when destroy interleaves an unlocked startAll")
  void shouldNotLeaveProducerRunningWhenDestroyInterleavesStartAll() throws Exception {
    var gate = new SecondStartGatingExecutor();
    var lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(gate)
            .segmentStore(new FakeSegmentStore())
            .properties(
                StreamingProperties.builder()
                    .maxConcurrentTranscodes(3)
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .sessionTimeout(Duration.ofSeconds(60))
                    .build())
            .runtimeRegistry(new FakeRuntimeStreamSessionRegistry())
            .sessionMutex(new MutexFactory<>())
            .build();
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

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var startAll =
          executor.submit(
              () -> {
                lifecycle.startAll(session, 0, 0);
                return null;
              });
      assertThat(gate.reachedSecondStart.await(5, TimeUnit.SECONDS))
          .as("startAll must reach the second variant start")
          .isTrue();

      // The destroy path a reaper or shutdown sweep takes for a published session.
      lifecycle.stopForDestroy(session.getSessionId());

      gate.releaseSecondStart.countDown();
      startAll.get(5, TimeUnit.SECONDS);
    }

    assertThat(gate.isRunning(session.getSessionId()))
        .as("a destroyed session must not retain a running producer (orphaned FFmpeg process)")
        .isFalse();
  }

  private static final class SecondStartGatingExecutor extends FakeTranscodeExecutor {

    private final CountDownLatch reachedSecondStart = new CountDownLatch(1);
    private final CountDownLatch releaseSecondStart = new CountDownLatch(1);
    private final AtomicInteger starts = new AtomicInteger();

    @Override
    public TranscodeHandle start(TranscodeRequest request) {
      if (starts.incrementAndGet() == 2) {
        reachedSecondStart.countDown();
        awaitRelease();
      }
      return super.start(request);
    }

    private void awaitRelease() {
      try {
        if (!releaseSecondStart.await(10, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Second-start gate was never released");
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
