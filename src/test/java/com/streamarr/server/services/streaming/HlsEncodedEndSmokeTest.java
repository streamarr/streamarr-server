package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecisionBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeExecutionRequest;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.ProbePriority;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fixtures.ServerSettingsFixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteFfprobeService;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
            .build();
    coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(lifecycle)
            .heldRequests(workerFixture.workerSessions().heldSegmentRequests())
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
    var source = generateSource("twelve-seconds-at-23.976.mp4", 12);
    var session = startedVideoEncode(source);
    var sessionId = session.getSessionId();
    var firstAttempt = session.getHandle().orElseThrow().attemptId();
    await()
        .atMost(60, TimeUnit.SECONDS)
        .until(() -> !workerFixture.worker().processRunning(firstAttempt));

    var advertised = advertisedSegmentNames(session);
    for (var segmentName : advertised) {
      assertThat(deliverWithin(sessionId, segmentName, Duration.ofSeconds(90)))
          .as("the delivery of advertised %s of %s", segmentName, advertised)
          .isInstanceOf(SegmentDelivery.Ready.class);
    }
  }

  private Path generateSource(String fileName, int durationSeconds) throws Exception {
    return workerFixture
        .worker()
        .generateMedia(
            sourceRoot.resolve(fileName),
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=320x180:rate=24000/1001:duration=" + durationSeconds,
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000:duration=" + durationSeconds,
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
  }

  private StreamSession startedVideoEncode(Path source) {
    var probe = probe(source);
    var session =
        defaultSessionBuilder()
            .sourcePath(source)
            .mediaProbe(probe)
            .transcodeDecision(
                remuxDecisionBuilder()
                    .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
                    .videoSource(probe.videoPlaybackFacts())
                    .build())
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

  private SegmentDelivery deliverWithin(UUID sessionId, String segmentName, Duration bound)
      throws Exception {
    return CompletableFuture.supplyAsync(
            () -> coordinator.deliver(sessionId, StreamSession.defaultVariant(), segmentName))
        .get(bound.toMillis(), TimeUnit.MILLISECONDS);
  }
}
