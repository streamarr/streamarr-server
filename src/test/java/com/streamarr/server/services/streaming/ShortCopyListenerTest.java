package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultLadderSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.sessionWithDurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.ShortCopy;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Short Copy Listener Tests")
class ShortCopyListenerTest {

  private final FakeSegmentStore segmentStore = new FakeSegmentStore();
  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
  private ProducerLifecycleService lifecycle;
  private ShortCopyListener listener;

  @BeforeEach
  void setUp() {
    lifecycle =
        StreamingRigFixture.streamingRigBuilder()
            .transcodeExecutor(new FakeTranscodeExecutor())
            .segmentStore(segmentStore)
            .properties(
                StreamingProperties.builder().targetSegmentDuration(Duration.ofSeconds(6)).build())
            .runtimeRegistry(runtimeRegistry)
            .build()
            .lifecycle();
    listener = new ShortCopyListener(lifecycle);
  }

  @Test
  @DisplayName(
      "Should end playback normally at the last stored segment's end when a completed copy stored too few segments")
  void shouldEndPlaybackNormallyAtTheLastStoredSegmentsEndWhenACompletedCopyStoredTooFewSegments() {
    var copy = started(defaultSessionBuilder().build());
    storeSegments(copy, 4);

    listener.onJobAttemptCompleted(completionBuilderOf(copy).build());

    assertThat(copy.getShortCopy()).contains(new ShortCopy(3, Duration.ofSeconds(24), true));
    assertThat(copy.getNormalEnd()).contains(Duration.ofSeconds(24));
  }

  @Test
  @DisplayName("Should show no short copy when the completed copy stored every advertised segment")
  void shouldShowNoShortCopyWhenTheCompletedCopyStoredEveryAdvertisedSegment() {
    var copy = started(sessionWithDurationBuilder(12).build());
    storeSegments(copy, 2);

    listener.onJobAttemptCompleted(completionBuilderOf(copy).build());

    assertThat(copy.getShortCopy()).isEmpty();
  }

  @Test
  @DisplayName("Should show no short copy when a job attempt that a replacement left completes")
  void shouldShowNoShortCopyWhenAJobAttemptThatAReplacementLeftCompletes() {
    var copy = started(defaultSessionBuilder().build());
    storeSegments(copy, 4);

    listener.onJobAttemptCompleted(
        completionBuilderOf(copy).jobAttemptId(UUID.randomUUID()).build());

    assertThat(copy.getShortCopy()).isEmpty();
  }

  @Test
  @DisplayName("Should show no short copy when a completed copy stored no media segment")
  void shouldShowNoShortCopyWhenACompletedCopyStoredNoMediaSegment() {
    var copy = started(defaultSessionBuilder().build());

    listener.onJobAttemptCompleted(completionBuilderOf(copy).build());

    assertThat(copy.getShortCopy()).isEmpty();
  }

  // Padding an encode past a source that ends early stays with streamarr/streamarr-server#511.
  @Test
  @DisplayName("Should show no short copy when an encode completes short")
  void shouldShowNoShortCopyWhenAnEncodeCompletesShort() {
    var encode = started(defaultLadderSessionBuilder().build());
    var variant = encode.getVariants().getFirst().label();
    segmentStore.addSegment(
        encode.getSessionId(), variant + "/" + SegmentNames.mediaSegmentName(0), new byte[] {1});

    listener.onJobAttemptCompleted(
        JobAttemptCompletedEvent.builder()
            .streamSessionId(encode.getSessionId())
            .jobAttemptId(encode.getVariantHandle(variant).orElseThrow().attemptId())
            .variantLabel(variant)
            .build());

    assertThat(encode.getShortCopy()).isEmpty();
  }

  // Every job attempt reads the same source, so a later completion cannot move the real end.
  @Test
  @DisplayName("Should keep the first short copy when a later job attempt completes")
  void shouldKeepTheFirstShortCopyWhenALaterJobAttemptCompletes() {
    var copy = started(defaultSessionBuilder().build());
    storeSegments(copy, 2);
    listener.onJobAttemptCompleted(completionBuilderOf(copy).build());
    var later = new TranscodeHandle(2L, UUID.randomUUID(), TranscodeStatus.ACTIVE, 0);
    copy.setHandle(later);
    storeSegments(copy, 5);

    listener.onJobAttemptCompleted(
        completionBuilderOf(copy).jobAttemptId(later.attemptId()).build());

    assertThat(copy.getShortCopy()).contains(new ShortCopy(1, Duration.ofSeconds(12), true));
  }

  @Test
  @DisplayName("Should show no short copy when the stream session ended before the completion")
  void shouldShowNoShortCopyWhenTheStreamSessionEndedBeforeTheCompletion() {
    var copy = started(defaultSessionBuilder().build());
    storeSegments(copy, 4);
    var completion = completionBuilderOf(copy).build();
    runtimeRegistry.removeById(copy.getSessionId());

    listener.onJobAttemptCompleted(completion);

    assertThat(copy.getShortCopy()).isEmpty();
  }

  private StreamSession started(StreamSession session) {
    runtimeRegistry.save(session);
    return lifecycle.startFirstListedVariant(session);
  }

  private void storeSegments(StreamSession session, int count) {
    for (var index = 0; index < count; index++) {
      segmentStore.addSegment(
          session.getSessionId(), SegmentNames.mediaSegmentName(index), new byte[] {1});
    }
  }

  // The completion of the default variant's current job attempt.
  private static JobAttemptCompletedEvent.JobAttemptCompletedEventBuilder completionBuilderOf(
      StreamSession session) {
    return JobAttemptCompletedEvent.builder()
        .streamSessionId(session.getSessionId())
        .jobAttemptId(session.getHandle().orElseThrow().attemptId())
        .variantLabel(StreamSession.defaultVariant());
  }
}
