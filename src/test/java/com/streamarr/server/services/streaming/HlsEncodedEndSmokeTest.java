package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.ClientCapabilityDeclarationFixture.defaultDeclarationBuilder;
import static com.streamarr.server.fixtures.SeekPriorityFixture.seekPriorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecisionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.ServerSettingsFixture;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-worker proof that an encoded variant serves every media segment that its playlist
 * advertises, up to the advertised end (ADR 0037, early ends).
 */
@Tag("SmokeTest")
@DisplayName("HLS Encoded End Smoke Tests")
class HlsEncodedEndSmokeTest {

  private static final Duration TARGET_SEGMENT_DURATION = Duration.ofSeconds(6);
  private static final String TOKEN = "smoke-token";
  private static final Duration DELIVERY_BOUND = Duration.ofSeconds(90);
  private static final int FRAMES_IN_A_SEGMENT_AT_24_FPS = 144;

  @TempDir private Path temporaryDirectory;
  private Path sourceRoot;
  private LocalSegmentStore segmentStore;
  private WorkerStreamingSmokeFixture workerFixture;
  private RemoteTranscodeExecutor transcodeExecutor;
  private final FakeRuntimeStreamSessionRegistry runtimeRegistry =
      new FakeRuntimeStreamSessionRegistry();
  private ProducerLifecycleService lifecycle;
  private SegmentDeliveryCoordinator coordinator;
  private HlsPlaylistService playlistService;
  private TranscodeDecisionService decisionService;

  @BeforeEach
  void setUp() throws Exception {
    sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    segmentStore = new LocalSegmentStore(temporaryDirectory.resolve("segments"));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
            .eventPublisher(this::settleCompletions)
            .build();
    workerFixture.start();
    transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot);
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(TARGET_SEGMENT_DURATION)
            .sessionTimeout(Duration.ofSeconds(120))
            .producerStallThreshold(Duration.ofSeconds(10))
            .build();
    lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .eventPublisher(new CapturingEventPublisher())
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(new MutexFactory<>())
            .serverSettings(ServerSettingsFixture.defaultServerSettings())
            .seekPriority(
                seekPriorityBuilder()
                    .transcodeExecutor(transcodeExecutor)
                    .segmentStore(segmentStore)
                    .runtimeRegistry(runtimeRegistry)
                    .properties(properties)
                    .build())
            .build();
    coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(lifecycle)
            .heldRequests(workerFixture.workerSessions().heldSegmentRequests())
            .eventPublisher(new CapturingEventPublisher())
            .build();
    playlistService = new HlsPlaylistService(properties);
    decisionService = new TranscodeDecisionService(properties, new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() {
    if (workerFixture == null) {
      return;
    }

    workerFixture.stopStreamSessionsAndClose(
        runtimeRegistry.findAll().stream().map(StreamSession::getSessionId).toList());
  }

  // 288 frames at 23.976 fps end the video at 12.012 s, and no frame starts after 12 s.
  @Test
  @DisplayName(
      "Should serve every advertised segment of an encode when the media ends a fraction of a"
          + " frame after a segment boundary")
  void shouldServeEveryAdvertisedSegmentOfAnEncodeWhenTheMediaEndsAFractionOfAFrameAfterABoundary()
      throws Exception {
    var source = generateSource("twelve-seconds-at-23.976.mp4", "24000/1001", 12, 12);
    var session = startedEncode(source, copiedAudioEncode());
    awaitFirstAttemptEnd(session);

    assertEveryAdvertisedSegmentIsServed(session);
  }

  @Test
  @DisplayName(
      "Should deliver one interval of audio within the startup threshold when six-channel audio"
          + " outlasts a 64-second video in a 1764-second source")
  void shouldDeliverOneIntervalOfAudioWithinTheStartupThresholdWhenSixChannelAudioOutlastsVideo()
      throws Exception {
    var session = startedSixChannelEncode(1_764);
    var segments = advertisedSegmentNames(session);
    assertThat(segments).hasSize(294);

    // The first two full tail intervals expose waiting for the remaining 28 minutes of audio.
    assertAudioIntervalsAreDelivered(session, segments.subList(11, 13));
  }

  @Test
  @DisplayName(
      "Should deliver only each interval's audio through EOF when six-channel audio outlasts a"
          + " 64-second video in a 96-second source")
  void shouldDeliverOnlyEachIntervalsAudioThroughEofWhenSixChannelAudioOutlastsVideo()
      throws Exception {
    var session = startedSixChannelEncode(96);
    var segments = advertisedSegmentNames(session);
    assertThat(segments).hasSize(16);

    assertAudioIntervalsAreDelivered(session, segments.subList(11, segments.size()));
    awaitFirstAttemptEnd(session);
  }

  @Test
  @EnabledIfSystemProperty(named = "streamarr.smoke.exhaustiveAudioTail", matches = "true")
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @DisplayName(
      "Should deliver every long-tail interval through EOF when exhaustive audio acceptance is"
          + " requested")
  void shouldDeliverEveryLongTailIntervalThroughEofWhenExhaustiveAudioAcceptanceIsRequested()
      throws Exception {
    var session = startedSixChannelEncode(1_764);
    var segments = advertisedSegmentNames(session);
    assertThat(segments).hasSize(294);

    assertAudioIntervalsAreDelivered(session, segments.subList(11, segments.size()));
    awaitFirstAttemptEnd(session);
  }

  private void assertAudioIntervalsAreDelivered(StreamSession session, List<String> segments)
      throws Exception {
    for (var segment : segments) {
      assertThat(deliveryOf(session, segment))
          .as("delivery of %s after the video's end", segment)
          .succeedsWithin(Duration.ofSeconds(10))
          .isInstanceOf(SegmentDelivery.Ready.class);
      // AAC carries 1,024 samples per packet; interval boundaries can split one packet.
      var media =
          Fmp4Fixture.withInitializationSegment(segmentStore, session.getSessionId(), segment);
      assertThat(Fmp4Fixture.audioSampleCount(media) * 1_024.0 / 48_000)
          .as("seconds of audio in %s", segment)
          .isCloseTo(6.0, within(1_024.0 / 48_000));
    }

    assertThat(audioPacketCount(session, segments.getFirst())).isBetween(281, 282);
    assertThat(audioPacketCount(session, segments.getLast())).isBetween(281, 282);
  }

  @Test
  @DisplayName(
      "Should serve every segment after the video's end with the held last frame when an encode's"
          + " video ends two segments before its audio")
  void shouldServeEverySegmentAfterTheVideosEndWithTheHeldLastFrameWhenAnEncodesVideoEndsFirst()
      throws Exception {
    var source = generateSource("video-ends-at-12-of-24.mp4", "24", 12, 24);
    var session = startedEncode(source, copiedAudioEncode());
    awaitFirstAttemptEnd(session);

    assertEveryAdvertisedSegmentIsServed(session);
    assertThat(decodedVideoFrames(session, "segment3.m4s"))
        .as("the held last frame fills the segment after the video's end")
        .isEqualTo(FRAMES_IN_A_SEGMENT_AT_24_FPS);
  }

  // Segment 7 starts 30 s after the video's end, past the running attempt's relocation gap, so the
  // request starts a job attempt there.
  @Test
  @DisplayName(
      "Should serve a segment long after the video's end with the held last frame when a player"
          + " seeks there")
  void shouldServeASegmentLongAfterTheVideosEndWithTheHeldLastFrameWhenAPlayerSeeksThere()
      throws Exception {
    var source = generateSource("video-ends-at-12-of-48.mp4", "24", 12, 48);
    var session = startedEncode(source, copiedAudioEncode());
    var firstAttempt = session.getHandle().orElseThrow().attemptId();

    assertThat(deliveryOf(session, "segment7.m4s"))
        .succeedsWithin(DELIVERY_BOUND)
        .isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().orElseThrow().attemptId()).isNotEqualTo(firstAttempt);
    assertThat(session.getHandle().orElseThrow().startSequenceNumber()).isEqualTo(7);
    assertThat(decodedVideoFrames(session, "segment7.m4s"))
        .isEqualTo(FRAMES_IN_A_SEGMENT_AT_24_FPS);
  }

  // A faststart MP4 cut to half its size still promises 30 s in its index, but its data ends near
  // 15 s, inside segment 2 of 5.
  @Test
  @DisplayName(
      "Should serve every advertised segment from the first job attempt when an encoded source is"
          + " shorter than its probe says")
  void shouldServeEveryAdvertisedSegmentFromTheFirstAttemptWhenAnEncodedSourceIsTruncated()
      throws Exception {
    var source = generateSource("truncated.mp4", "24", 30, 30, "-movflags", "+faststart");
    var probe = probe(source);
    truncateToHalf(source);
    var session = startedEncode(source, probe, encodedAudioEncode());
    var firstAttempt = session.getHandle().orElseThrow().attemptId();
    awaitFirstAttemptEnd(session);

    assertEveryAdvertisedSegmentIsServed(session);
    assertThat(session.getHandle().orElseThrow().attemptId())
        .as("the job attempt that served the segments past the source's real end")
        .isEqualTo(firstAttempt);
    assertThat(decodedVideoFrames(session, "segment4.m4s")).isPositive();
  }

  // The client declares the source's H.264 and AAC, so it could copy either stream.
  @Test
  @DisplayName(
      "Should carry audio in every segment after the audio's end when a client that copies both"
          + " codecs plays a source whose audio ends two segments before its video")
  void
      shouldCarryAudioInEverySegmentAfterTheAudiosEndWhenACopyCapableClientPlaysAudioThatEndsFirst()
          throws Exception {
    var source = generateSource("audio-ends-at-12-of-24.mp4", "24", 24, 12);
    var probe = probe(source);
    var session = startedEncode(source, probe, decidedForACopyCapableClient(probe));
    awaitFirstAttemptEnd(session);

    assertEveryAdvertisedSegmentIsServed(session);
    assertThat(advertisedSegmentNames(session)).hasSize(4);
    assertThat(audioPacketCount(session, "segment3.m4s"))
        .as("the silence that pads the audio fills the last segment")
        .isPositive();
  }

  @Test
  @DisplayName(
      "Should hold the last frame in every segment after the video's end when a client that copies"
          + " both codecs plays a source whose video ends two segments before its audio")
  void shouldHoldTheLastFrameAfterTheVideosEndWhenACopyCapableClientPlaysVideoThatEndsFirst()
      throws Exception {
    var source = generateSource("copyable-video-ends-at-12-of-24.mp4", "24", 12, 24);
    var probe = probe(source);
    var session = startedEncode(source, probe, decidedForACopyCapableClient(probe));
    awaitFirstAttemptEnd(session);

    assertEveryAdvertisedSegmentIsServed(session);
    assertThat(advertisedSegmentNames(session)).hasSize(4);
    assertThat(decodedVideoFrames(session, "segment3.m4s"))
        .as("the held last frame fills the last segment")
        .isEqualTo(FRAMES_IN_A_SEGMENT_AT_24_FPS);
  }

  private TranscodeDecision.TranscodeDecisionBuilder decidedForACopyCapableClient(
      MediaProbe probe) {
    return decisionService
        .decide(
            TranscodeDecisionRequest.builder()
                .source(probe)
                .capabilityDeclaration(defaultDeclarationBuilder().build())
                .encodableVideoCodecFamilies(Set.of("h264"))
                .build())
        .orElseThrow()
        .toBuilder();
  }

  @Test
  @DisplayName(
      "Should serve a segment past an encoded source's real end from a job attempt at the last"
          + " segment with source media when the segment is requested again")
  void shouldServeASegmentPastTheRealEndFromAnAttemptAtTheLastSegmentWithSourceMedia()
      throws Exception {
    var source = generateSource("truncated-again.mp4", "24", 30, 30, "-movflags", "+faststart");
    var probe = probe(source);
    truncateToHalf(source);
    var session = startedEncode(source, probe, encodedAudioEncode());
    awaitFirstAttemptEnd(session);
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> session.getLastSegmentWithSourceMedia().isPresent());
    Files.delete(segmentStore.getOutputDirectory(session.getSessionId()).resolve("segment4.m4s"));

    assertThat(deliveryOf(session, "segment4.m4s"))
        .succeedsWithin(DELIVERY_BOUND)
        .isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(session.getHandle().orElseThrow().startSequenceNumber())
        .isEqualTo(session.getLastSegmentWithSourceMedia().getAsInt());
    assertThat(decodedVideoFrames(session, "segment4.m4s"))
        .isEqualTo(FRAMES_IN_A_SEGMENT_AT_24_FPS);
  }

  private void settleCompletions(Object event) {
    if (event instanceof JobAttemptCompletedEvent completed && lifecycle != null) {
      new ShortCopyListener(lifecycle, _ -> {}).onJobAttemptCompleted(completed);
    }
  }

  private void awaitFirstAttemptEnd(StreamSession session) {
    var firstAttempt = session.getHandle().orElseThrow().attemptId();
    await()
        .atMost(60, TimeUnit.SECONDS)
        .until(() -> !workerFixture.worker().processRunning(firstAttempt));
  }

  private void assertEveryAdvertisedSegmentIsServed(StreamSession session) {
    var advertised = advertisedSegmentNames(session);
    for (var segmentName : advertised) {
      assertThat(deliveryOf(session, segmentName))
          .succeedsWithin(DELIVERY_BOUND)
          .as("the delivery of advertised %s of %s", segmentName, advertised)
          .isInstanceOf(SegmentDelivery.Ready.class);
    }
  }

  private long decodedVideoFrames(StreamSession session, String segmentName) throws Exception {
    var media =
        Files.write(
            temporaryDirectory.resolve("decodable-" + segmentName + ".mp4"),
            Fmp4Fixture.withInitializationSegment(
                segmentStore, session.getSessionId(), segmentName));
    return workerFixture.worker().decodedVideoFrameCount(media);
  }

  private int audioPacketCount(StreamSession session, String segmentName) throws Exception {
    var media =
        Files.write(
            temporaryDirectory.resolve("audible-" + segmentName + ".mp4"),
            Fmp4Fixture.withInitializationSegment(
                segmentStore, session.getSessionId(), segmentName));
    return workerFixture.worker().audioPacketHashes(media).size();
  }

  private Path generateSource(
      String fileName,
      String frameRate,
      int videoSeconds,
      int audioSeconds,
      String... outputArguments)
      throws Exception {
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=" + frameRate + ":duration=" + videoSeconds,
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000:duration=" + audioSeconds,
                "-map",
                "0:v",
                "-map",
                "1:a",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "48",
                "-c:a",
                "aac"));
    arguments.addAll(List.of(outputArguments));
    return workerFixture.worker().generateMedia(sourceRoot.resolve(fileName), arguments);
  }

  private StreamSession startedSixChannelEncode(int audioSeconds) throws Exception {
    var source = generateSixChannelSource(audioSeconds);
    var probe = probe(source);
    assertThat(probe.audioChannels()).hasValue(6);
    return startedEncode(source, probe, encodedAudioEncode());
  }

  private Path generateSixChannelSource(int audioSeconds) throws Exception {
    var tone = sourceRoot.resolve("six-channel-tone.m4a");
    workerFixture
        .worker()
        .generateMedia(
            tone,
            List.of(
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000:duration=2.048",
                "-ac",
                "6",
                "-c:a",
                "aac",
                "-use_editlist",
                "0"));
    // Omit the seed's priming edit list so repeated packets form one continuous timeline.
    // Stream-copying them preserves a long decode/downmix workload without a long fixture encode.
    return workerFixture
        .worker()
        .generateMedia(
            sourceRoot.resolve("video-ends-at-64-of-" + audioSeconds + ".mp4"),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=64x36:rate=24:duration=64",
                "-stream_loop",
                "-1",
                "-i",
                workerFixture.worker().containerPath(tone),
                "-map",
                "0:v",
                "-map",
                "1:a",
                "-c:v",
                "libx264",
                "-pix_fmt",
                "yuv420p",
                "-g",
                "48",
                "-c:a",
                "copy",
                "-t",
                String.valueOf(audioSeconds)));
  }

  private static void truncateToHalf(Path source) throws Exception {
    try (var channel = FileChannel.open(source, StandardOpenOption.WRITE)) {
      channel.truncate(channel.size() / 2);
    }
  }

  private static TranscodeDecision.TranscodeDecisionBuilder copiedAudioEncode() {
    return remuxDecisionBuilder().transcodeMode(TranscodeMode.VIDEO_TRANSCODE);
  }

  private static TranscodeDecision.TranscodeDecisionBuilder encodedAudioEncode() {
    return remuxDecisionBuilder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .audioDecision(AudioDecision.stereoAac());
  }

  private StreamSession startedEncode(
      Path source, TranscodeDecision.TranscodeDecisionBuilder decision) {
    return startedEncode(source, probe(source), decision);
  }

  private StreamSession startedEncode(
      Path source, MediaProbe probe, TranscodeDecision.TranscodeDecisionBuilder decision) {
    var session =
        defaultSessionBuilder()
            .sourcePath(source)
            .mediaProbe(probe)
            .transcodeDecision(decision.videoSource(probe.videoPlaybackFacts()).build())
            .build();
    runtimeRegistry.save(session);
    lifecycle.startFirstListedVariant(session);
    return session;
  }

  private MediaProbe probe(Path source) {
    return switch (new RemoteFfprobeService(
            workerFixture.workerSessions(), workerFixture.sourceNamespaceId(), sourceRoot)
        .probe(
            ProbeExecutionRequest.builder()
                .sourcePath(source)
                .attemptId(UUID.randomUUID())
                .probeVersion(ProbeVersion.CURRENT)
                .priority(ProbePriority.BACKGROUND)
                .build())) {
      case ProbeOutcome.Success success -> success.mediaProbe();
      case ProbeOutcome.Failure failure -> throw new AssertionError(failure);
    };
  }

  private List<String> advertisedSegmentNames(StreamSession session) {
    var suffix = "?t=" + TOKEN;
    return playlistService
        .generateMediaPlaylist(session, StreamSession.defaultVariant(), TOKEN)
        .lines()
        .filter(line -> line.startsWith("segment") && line.endsWith(suffix))
        .map(line -> line.substring(0, line.length() - suffix.length()))
        .toList();
  }

  private CompletableFuture<SegmentDelivery> deliveryOf(StreamSession session, String segmentName) {
    return CompletableFuture.supplyAsync(
        () ->
            coordinator.deliver(
                session.getSessionId(), StreamSession.defaultVariant(), segmentName));
  }
}
