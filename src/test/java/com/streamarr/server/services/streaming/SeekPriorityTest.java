package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.SeekPriorityFixture.seekPriorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.mintHandle;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import com.streamarr.server.support.BoundedTask;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Seek Priority Tests")
class SeekPriorityTest {

  private static final Duration BOUND = Duration.ofSeconds(2);

  private final FakeTranscodeExecutor transcodeExecutor = new FakeTranscodeExecutor();
  private final FakeSegmentStore segmentStore = new FakeSegmentStore();
  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
  private final SeekPriority seekPriority =
      seekPriorityBuilder()
          .transcodeExecutor(transcodeExecutor)
          .segmentStore(segmentStore)
          .runtimeRegistry(runtimeRegistry)
          .properties(StreamingProperties.builder().build())
          .build();

  @Test
  @DisplayName(
      "Should take another stream session's media segment request while a variant change waits to"
          + " learn which worker runs a seek's job attempt")
  void shouldTakeAnotherStreamSessionsRequestWhileAVariantChangeWaitsToLearnWhichWorkerRunsASeek() {
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

  // The job attempt starts at segment 10 and stores it. Segment 9 is a segment of an earlier job
  // attempt.
  @Test
  @DisplayName(
      "Should say that the running job attempt stored no media segment before its first one when"
          + " an earlier job attempt stored that segment")
  void
      shouldSayThatTheRunningJobAttemptStoredNoMediaSegmentBeforeItsFirstOneWhenAnEarlierJobAttemptStoredThatSegment() {
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    var handle = TranscodeHandle.remoteDispatch(UUID.randomUUID(), TranscodeStatus.ACTIVE, 10);
    session.setHandle(handle);
    runtimeRegistry.save(session);
    segmentStore.addSegment(sessionId, "segment9.m4s", new byte[] {1});
    seekPriority.jobAttemptStarted(session, StreamSession.defaultVariant(), handle);
    segmentStore.addSegment(sessionId, "segment10.m4s", new byte[] {1});
    seekPriority.mediaSegmentPublished(
        MediaSegmentPublishedEvent.builder()
            .streamSessionId(sessionId)
            .jobAttemptId(handle.attemptId())
            .variantLabel(StreamSession.defaultVariant())
            .segmentIndex(10)
            .executionTarget(new ExecutionTargetId("worker-a"))
            .build());

    var storedSegmentBeforeItsFirst =
        seekPriority.runningJobAttemptHasStored(session, StreamSession.defaultVariant(), 9);
    var storedItsFirstSegment =
        seekPriority.runningJobAttemptHasStored(session, StreamSession.defaultVariant(), 10);

    assertThat(storedSegmentBeforeItsFirst).isFalse();
    assertThat(storedItsFirstSegment).isTrue();
  }

  // A job attempt replaces another in two steps: seek priority learns of it, then the stream
  // session names it. Between the steps the session still names the job attempt at segment 0,
  // which has stored nothing. Segment 10 is a segment of an earlier job attempt.
  @Test
  @DisplayName(
      "Should say that the running job attempt stored no media segment when seek priority knows"
          + " only the job attempt that replaces it")
  void
      shouldSayThatTheRunningJobAttemptStoredNoMediaSegmentWhenSeekPriorityKnowsOnlyTheJobAttemptThatReplacesIt() {
    var session = defaultSessionBuilder().build();
    var sessionId = session.getSessionId();
    session.setHandle(TranscodeHandle.remoteDispatch(UUID.randomUUID(), TranscodeStatus.ACTIVE, 0));
    runtimeRegistry.save(session);
    segmentStore.addSegment(sessionId, "segment10.m4s", new byte[] {1});
    var replacement = TranscodeHandle.remoteDispatch(UUID.randomUUID(), TranscodeStatus.ACTIVE, 20);
    seekPriority.jobAttemptStarted(session, StreamSession.defaultVariant(), replacement);

    var storedSegment =
        seekPriority.runningJobAttemptHasStored(session, StreamSession.defaultVariant(), 10);

    assertThat(storedSegment).isFalse();
  }

  @Test
  @DisplayName(
      "Should refuse to say what a running job attempt stored when the stream session or the"
          + " variant is missing")
  void shouldRefuseToSayWhatARunningJobAttemptStoredWhenTheStreamSessionOrTheVariantIsMissing() {
    var session = defaultSessionBuilder().build();
    var variantLabel = StreamSession.defaultVariant();

    assertThatNullPointerException()
        .isThrownBy(() -> seekPriority.runningJobAttemptHasStored(null, variantLabel, 0))
        .withMessageContaining("session");
    assertThatNullPointerException()
        .isThrownBy(() -> seekPriority.runningJobAttemptHasStored(session, null, 0))
        .withMessageContaining("variantLabel");
  }
}
