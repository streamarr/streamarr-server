package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.streaming.ProducerLifecycleService.RecoveryResult;
import com.streamarr.server.services.streaming.events.MediaSegmentPublishedEvent;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Forward Seek Past An Earlier Job Attempt's Output Tests")
class ForwardSeekPastEarlierJobAttemptOutputTest {

  private static final String VARIANT = StreamSession.defaultVariant();

  private FakeTranscodeExecutor transcodeExecutor;
  private FakeSegmentStore segmentStore;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;
  private SegmentDeliveryCoordinator coordinator;
  private MutableClock clock;

  @BeforeEach
  void setUp() {
    clock = new MutableClock();
    transcodeExecutor = new FakeTranscodeExecutor();
    segmentStore = new FakeSegmentStore();
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder()
                    .maxConcurrentTranscodes(3)
                    .targetSegmentDuration(Duration.ofSeconds(6))
                    .sessionTimeout(Duration.ofSeconds(60))
                    .build())
            .clock(clock)
            .runtimeRegistry(runtimeRegistry)
            .build();
    lifecycle = rig.lifecycle();
    coordinator = rig.coordinator();
  }

  // ADR 0019 measures the relocation gap against the running job attempt's output. Segments
  // 200..230 are an earlier job attempt's, so the attempt that runs from segment 31 is 190
  // segments behind the request and the server must start a new job attempt at segment 231.
  @Test
  @DisplayName(
      "Should relocate when the segment one relocation gap before the request was stored by an"
          + " earlier job attempt, not by the running one")
  void shouldRelocateWhenTheSegmentOneGapBeforeTheRequestWasStoredByAnEarlierJobAttempt() {
    var session = sessionPlayedFromTheStartThenSoughtTo200ThenBackTo28();
    var sessionId = session.getSessionId();
    var runningAttempt = session.getHandle().orElseThrow();
    assertThat(runningAttempt.startSequenceNumber()).isEqualTo(31);
    assertThat(transcodeExecutor.getStartedRequests()).hasSize(3);
    for (var index = 205; index <= 230; index++) {
      assertThat(coordinator.deliver(sessionId, VARIANT, segmentName(index)))
          .isInstanceOf(SegmentDelivery.Ready.class);
    }

    assertThat(transcodeExecutor.getStartedRequests()).hasSize(3);

    var result = requestOnce(sessionId, 231);

    assertThat(result).isEqualTo(RecoveryResult.WAITING);
    assertThat(transcodeExecutor.getStartedRequests())
        .as("job attempts started; the last one must start at the requested segment 231")
        .hasSize(4);
    assertThat(transcodeExecutor.getStartedRequests().getLast().startSequenceNumber())
        .isEqualTo(231);
  }

  // Job attempt A stores 0..30, a seek to 200 starts B, which stores 200..230, and a seek back to
  // 28 plays the stored 28..30 and starts C at 31, which has stored 31..40.
  private StreamSession sessionPlayedFromTheStartThenSoughtTo200ThenBackTo28() {
    var session = defaultSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    var sessionId = session.getSessionId();
    publishFromRunningAttempt(session, 0, 30);

    assertThat(requestOnce(sessionId, 200)).isEqualTo(RecoveryResult.WAITING);
    assertThat(session.getHandle().orElseThrow().startSequenceNumber()).isEqualTo(200);
    publishFromRunningAttempt(session, 200, 230);

    for (var index = 28; index <= 30; index++) {
      assertThat(coordinator.deliver(sessionId, VARIANT, segmentName(index)))
          .isInstanceOf(SegmentDelivery.Ready.class);
    }

    assertThat(requestOnce(sessionId, 31)).isEqualTo(RecoveryResult.WAITING);
    publishFromRunningAttempt(session, 31, 40);
    return session;
  }

  // One pass of SegmentDeliveryCoordinator.deliver for a segment that is not stored: the request
  // is prepared, then recovery decides whether it waits, relocates or replaces.
  private RecoveryResult requestOnce(UUID sessionId, int index) {
    var request = lifecycle.prepareRequestedVariant(sessionId, VARIANT, segmentName(index));
    return lifecycle.recover(request);
  }

  private void publishFromRunningAttempt(StreamSession session, int first, int last) {
    var sessionId = session.getSessionId();
    var attemptId = session.getHandle().orElseThrow().attemptId();
    var worker = transcodeExecutor.runningExecutionTarget(sessionId, VARIANT).orElseThrow();
    for (var index = first; index <= last; index++) {
      segmentStore.addSegment(sessionId, segmentName(index), new byte[] {1});
      lifecycle.mediaSegmentPublished(
          MediaSegmentPublishedEvent.builder()
              .streamSessionId(sessionId)
              .jobAttemptId(attemptId)
              .variantLabel(VARIANT)
              .segmentIndex(index)
              .executionTarget(worker)
              .build());
    }
  }

  private static String segmentName(int index) {
    return SegmentNames.mediaSegmentName(index);
  }
}
