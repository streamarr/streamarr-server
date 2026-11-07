package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.SeekPriorityFixture.seekPriorityBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecisionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
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
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

  @BeforeEach
  void setUp() throws Exception {
    sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    segmentStore = new LocalSegmentStore(temporaryDirectory.resolve("segments"));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
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
