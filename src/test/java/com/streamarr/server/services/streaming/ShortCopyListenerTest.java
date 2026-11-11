package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ProbeFixture.audioStreamBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultLadderSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.sessionWithDurationBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ShortCopy;
import com.streamarr.server.domain.streaming.SourceEndedEarly;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fakes.FakeTranscodeExecutor;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.streaming.events.CopiedVariantRanShortEvent;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Short Copy Listener Tests")
class ShortCopyListenerTest {

  private final FakeSegmentStore segmentStore = new FakeSegmentStore();
  private final CapturingEventPublisher events = new CapturingEventPublisher();
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
    listener = new ShortCopyListener(lifecycle, events);
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
    assertThat(events.getEventsOfType(CopiedVariantRanShortEvent.class)).isEmpty();
  }

  // ADR 0052: a short copy that a source stream outlasts is a format failure of the copy.
  @Test
  @DisplayName(
      "Should publish the failed copy when a source stream still has media after the copy's end")
  void shouldPublishTheFailedCopyWhenASourceStreamStillHasMediaAfterTheCopysEnd() {
    var copy = started(defaultSessionBuilder().mediaProbe(probeWithMediaAfter24Seconds()).build());
    storeSegments(copy, 4);
    var completion = completionBuilderOf(copy).build();

    listener.onJobAttemptCompleted(completion);

    assertThat(copy.getShortCopy()).contains(new ShortCopy(3, Duration.ofSeconds(24), false));
    assertThat(copy.getNormalEnd()).isEmpty();
    assertThat(events.getEventsOfType(CopiedVariantRanShortEvent.class))
        .containsExactly(
            CopiedVariantRanShortEvent.builder()
                .streamSessionId(completion.streamSessionId())
                .jobAttemptId(completion.jobAttemptId())
                .variantLabel(completion.variantLabel())
                .build());
  }

  // A stream whose header states no end time counts as ended by the probe alone, but the worker
  // read the source past the copy's end (ADR 0037, early ends).
  @Test
  @DisplayName(
      "Should publish the failed copy when the copy's report names a stream with media after its"
          + " last segment, although the probe stored no end time")
  void shouldPublishTheFailedCopyWhenTheCopysReportNamesAStreamWithMediaAfterItsLastSegment() {
    var copy = started(defaultSessionBuilder().mediaProbe(probeWithoutStreamEndTimes()).build());
    storeSegments(copy, 4);

    listener.onJobAttemptCompleted(
        completionBuilderOf(copy)
            .sourceEndedEarly(Optional.of(new SourceEndedEarly(3, List.of(1))))
            .build());

    assertThat(copy.getShortCopy()).contains(new ShortCopy(3, Duration.ofSeconds(24), false));
    assertThat(copy.getNormalEnd()).isEmpty();
    assertThat(events.getEventsOfType(CopiedVariantRanShortEvent.class)).hasSize(1);
  }

  @Test
  @DisplayName(
      "Should end playback normally when the copy's report names no stream with media after its"
          + " last segment and the probe stored no end time")
  void shouldEndPlaybackNormallyWhenTheCopysReportNamesNoStreamWithMediaAfterItsLastSegment() {
    var copy = started(defaultSessionBuilder().mediaProbe(probeWithoutStreamEndTimes()).build());
    storeSegments(copy, 4);

    listener.onJobAttemptCompleted(
        completionBuilderOf(copy)
            .sourceEndedEarly(Optional.of(new SourceEndedEarly(3, List.of())))
            .build());

    assertThat(copy.getNormalEnd()).contains(Duration.ofSeconds(24));
    assertThat(events.getEventsOfType(CopiedVariantRanShortEvent.class)).isEmpty();
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

  // Every job attempt reads the same source, so a later completion moves neither the copy's end
  // nor starts a second fallback.
  @Test
  @DisplayName("Should keep the first short copy when a later job attempt of the copy completes")
  void shouldKeepTheFirstShortCopyWhenALaterJobAttemptOfTheCopyCompletes() {
    var copy = started(defaultSessionBuilder().mediaProbe(probeWithMediaAfter24Seconds()).build());
    storeSegments(copy, 2);
    listener.onJobAttemptCompleted(completionBuilderOf(copy).build());
    var later = new TranscodeHandle(2L, UUID.randomUUID(), TranscodeStatus.ACTIVE, 0);
    copy.setHandle(later);
    storeSegments(copy, 3);

    listener.onJobAttemptCompleted(
        completionBuilderOf(copy).jobAttemptId(later.attemptId()).build());

    assertThat(copy.getShortCopy()).contains(new ShortCopy(1, Duration.ofSeconds(12), false));
    assertThat(events.getEventsOfType(CopiedVariantRanShortEvent.class)).hasSize(1);
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

  // An audio stream that ends just over one target segment duration after the fourth segment.
  // The audio is stream 1: stream 0 is the default probe's chosen video stream, whose end the
  // probe does not state, so the media ends at the container's duration.
  private static MediaProbe probeWithMediaAfter24Seconds() {
    return defaultProbeBuilder()
        .streams(
            List.of(
                audioStreamBuilder("aac")
                    .index(1)
                    .endTime(Optional.of(Duration.ofMillis(30_001)))
                    .build()))
        .build();
  }

  private static MediaProbe probeWithoutStreamEndTimes() {
    return defaultProbeBuilder()
        .streams(List.of(audioStreamBuilder("aac").index(1).endTime(Optional.empty()).build()))
        .build();
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
