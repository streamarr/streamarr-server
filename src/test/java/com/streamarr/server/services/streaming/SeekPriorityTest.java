package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.SeekPriorityFixture.seekPriorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.support.BoundedTask;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Seek Priority Tests")
class SeekPriorityTest {

  private static final Duration BOUND = Duration.ofSeconds(2);

  private final FakeTranscodeExecutor transcodeExecutor = new FakeTranscodeExecutor();
  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
  private final SeekPriority seekPriority =
      seekPriorityBuilder()
          .transcodeExecutor(transcodeExecutor)
          .segmentStore(new FakeSegmentStore())
          .runtimeRegistry(runtimeRegistry)
          .properties(StreamingProperties.builder().build())
          .build();

  @Test
  @DisplayName(
      "Should take another stream session's media segment request while a variant change waits to"
          + " learn which worker runs a seek's job attempt")
  void shouldTakeAnotherStreamSessionsRequestWhileAVariantChangeWaitsToLearnWhichWorkerRunsASeek()
      throws Exception {
    var seeking = defaultSessionBuilder().build();
    seeking.setHandle(mintHandle(1L, TranscodeStatus.ACTIVE));
    runtimeRegistry.save(seeking);
    var other = defaultSessionBuilder().build();
    runtimeRegistry.save(other);
    seekPriority.seekStarted(seeking, StreamSession.defaultVariant(), 100);
    var lookupWaits = new CountDownLatch(1);
    var lookupMayAnswer = new CountDownLatch(1);
    transcodeExecutor.runBeforeNextRunningExecutionTargetRead(
        () -> {
          lookupWaits.countDown();
          try {
            lookupMayAnswer.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }
        });

    try (var change =
        BoundedTask.start(
            () -> seekPriority.variantChanged(seeking, StreamSession.defaultVariant()))) {
      await().atMost(BOUND).until(() -> lookupWaits.getCount() == 0);

      try (var request =
          BoundedTask.start(
              () -> seekPriority.mediaSegmentRequested(other.getSessionId(), 3, 1L))) {

        assertThatCode(() -> request.await(BOUND)).doesNotThrowAnyException();
        lookupMayAnswer.countDown();
        assertThatCode(() -> change.await(BOUND)).doesNotThrowAnyException();
      }
    } finally {
      lookupMayAnswer.countDown();
    }
  }
}
