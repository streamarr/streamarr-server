package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultLadderSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.streaming.events.HardwareEncoderFailedEvent;
import com.streamarr.server.services.streaming.events.RequestedVariantHardwareEncoderFailedEvent;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// ADR 0044: only the variant that the player requests continues in software. A left variant whose
// hardware encoder fails stops, and the stream session keeps playing.
@Tag("UnitTest")
@DisplayName("Hardware Encoder Failure Listener Tests")
class HardwareEncoderFailureListenerTest {

  private final CapturingEventPublisher events = new CapturingEventPublisher();
  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
  private ProducerLifecycleService lifecycle;
  private HardwareEncoderFailureListener listener;

  @BeforeEach
  void setUp() {
    lifecycle =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(new FakeTranscodeExecutor())
            .segmentStore(new FakeSegmentStore())
            .properties(
                StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build())
            .runtimeRegistry(runtimeRegistry)
            .build()
            .lifecycle();
    listener = new HardwareEncoderFailureListener(lifecycle, events);
  }

  @Test
  @DisplayName(
      "Should continue in software when the hardware encoder of the variant that the player"
          + " requests fails")
  void shouldContinueInSoftwareWhenTheHardwareEncoderOfTheRequestedVariantFails() {
    var session = startedLadderSession();
    lifecycle.prepareRequestedVariant(session.getSessionId(), "480p", "480p/segment0.m4s");
    var failure = failureOf(session, "480p");

    listener.onHardwareEncoderFailed(failure);

    assertThat(events.getEventsOfType(RequestedVariantHardwareEncoderFailedEvent.class))
        .containsExactly(
            RequestedVariantHardwareEncoderFailedEvent.builder()
                .streamSessionId(failure.streamSessionId())
                .jobAttemptId(failure.jobAttemptId())
                .variantLabel("480p")
                .build());
  }

  @Test
  @DisplayName(
      "Should continue in software when the first-listed variant fails before the player"
          + " requests any media segment")
  void shouldContinueInSoftwareWhenTheFirstListedVariantFailsBeforeAnyMediaSegmentRequest() {
    var session = startedLadderSession();

    listener.onHardwareEncoderFailed(failureOf(session, "480p"));

    assertThat(events.getEventsOfType(RequestedVariantHardwareEncoderFailedEvent.class)).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should keep the stream session playing when a left variant's hardware encoder fails")
  void shouldKeepTheStreamSessionPlayingWhenALeftVariantsHardwareEncoderFails() {
    var session = startedLadderSession();
    var sessionId = session.getSessionId();
    lifecycle.completeVariantSwitch(
        lifecycle.prepareRequestedVariant(sessionId, "480p", "480p/segment0.m4s"));
    lifecycle.prepareRequestedVariant(sessionId, "1080p", "1080p/segment1.m4s");

    listener.onHardwareEncoderFailed(failureOf(session, "480p"));

    assertThat(events.getEventsOfType(RequestedVariantHardwareEncoderFailedEvent.class)).isEmpty();
  }

  private StreamSession startedLadderSession() {
    var session = defaultLadderSessionBuilder().build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private static HardwareEncoderFailedEvent failureOf(StreamSession session, String variantLabel) {
    return HardwareEncoderFailedEvent.builder()
        .streamSessionId(session.getSessionId())
        .jobAttemptId(UUID.randomUUID())
        .variantLabel(variantLabel)
        .build();
  }
}
