package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-FFmpeg proof of ADR 0019's recovery contract: after a producer dies mid-stream, the next
 * request starts a replacement attempt at the requested segment's offset, and its timestamps
 * continue on media time. Input {@code -ss} with {@code -copyts} and {@code -start_at_zero} put
 * every job attempt's timestamps on media time.
 */
@Tag("SmokeTest")
@DisplayName("HLS Recovery Continuity Smoke Tests")
class HlsRecoveryContinuitySmokeTest {

  private static final Path TEST_VIDEO =
      Path.of("src/test/resources/BigBuckBunny_320x180_10s.mp4").toAbsolutePath();
  private static final int SEGMENT_DURATION_SECONDS = 2;

  private LocalSegmentStore segmentStore;
  private RemoteTranscodeExecutor transcodeExecutor;
  private WorkerStreamingSmokeFixture workerFixture;
  private FakeRuntimeStreamSessionRegistry runtimeRegistry;
  private ProducerLifecycleService lifecycle;
  private SegmentDeliveryCoordinator coordinator;
  @TempDir private Path temporaryDirectory;
  private Path testVideo;

  @BeforeEach
  void setUp() throws Exception {
    assertThat(TEST_VIDEO).isRegularFile();
    var sourceRoot = Files.createDirectory(temporaryDirectory.resolve("media"));
    testVideo = Files.copy(TEST_VIDEO, sourceRoot.resolve(TEST_VIDEO.getFileName()));
    var segmentBaseDir = Files.createDirectory(temporaryDirectory.resolve("segments"));
    segmentStore = new LocalSegmentStore(segmentBaseDir.resolve("server"));
    workerFixture =
        WorkerStreamingSmokeFixture.builder()
            .sourceRoot(sourceRoot)
            .segmentStore(segmentStore)
            .build();
    workerFixture.start();
    transcodeExecutor =
        new RemoteTranscodeExecutor(
            workerFixture.workerSessions(),
            workerFixture.sourceNamespaceId(),
            testVideo.getParent());
    runtimeRegistry = new FakeRuntimeStreamSessionRegistry();
    var properties =
        StreamingProperties.builder()
            .maxConcurrentTranscodes(3)
            .targetSegmentDuration(Duration.ofSeconds(SEGMENT_DURATION_SECONDS))
            .sessionTimeout(Duration.ofSeconds(60))
            .producerStallThreshold(Duration.ofSeconds(10))
            .build();
    lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(transcodeExecutor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(runtimeRegistry)
            .sessionMutex(new MutexFactory<>())
            .build();
    coordinator =
        SegmentDeliveryCoordinator.builder()
            .segmentStore(segmentStore)
            .producerLifecycle(lifecycle)
            .build();
  }

  @AfterEach
  void tearDown() {
    try {
      if (runtimeRegistry != null) {
        runtimeRegistry.findAll().stream()
            .map(StreamSession::getSessionId)
            .forEach(transcodeExecutor::stop);
      }
    } finally {
      try {
        if (workerFixture != null) {
          workerFixture.close();
        }
      } finally {
        if (segmentStore != null) {
          segmentStore.shutdown();
        }
      }
    }
  }

  @Test
  @DisplayName("Should continue the absolute timeline when a dead producer is replaced")
  void shouldContinueAbsoluteTimelineWhenDeadProducerIsReplaced() throws Exception {
    var session = startedSession(remuxDecision());
    assertThat(session.getHandle().orElseThrow().processId()).isEmpty();
    var sessionId = session.getSessionId();
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                segmentStore.segmentExists(sessionId, "segment0.m4s")
                    && segmentStore.segmentExists(sessionId, "segment1.m4s"));

    killProducerAndDropSegmentsFrom(session, 2);

    var delivery = coordinator.deliver(sessionId, StreamSession.defaultVariant(), "segment2.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    var replacementCommand =
        workerFixture
            .worker()
            .commandFor(session.getHandle().orElseThrow().attemptId())
            .orElseThrow();
    assertThat(replacementCommand)
        .containsSubsequence("-ss", String.valueOf(2 * SEGMENT_DURATION_SECONDS));

    // fMP4 output adds no muxer offset, so each job attempt's timestamps are media time itself.
    var initialTimestamps = packetTimestamps(sessionId, "segment0.m4s");
    var lastTimestampBeforeDeath = packetTimestamps(sessionId, "segment1.m4s").getLast();
    var replacementTimestamps = packetTimestamps(sessionId, "segment2.m4s");
    assertThat(initialTimestamps.getFirst()).isCloseTo(0.0, offset(0.1));
    assertThat(replacementTimestamps.getFirst())
        .isCloseTo(2.0 * SEGMENT_DURATION_SECONDS, offset(0.5));
    assertThat(replacementTimestamps.getFirst())
        .isGreaterThanOrEqualTo(lastTimestampBeforeDeath - 0.1);

    // The replacement emits a contiguous run from the requested index, never a lone segment.
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(() -> segmentStore.segmentExists(sessionId, "segment3.m4s"));
  }

  @Test
  @DisplayName("Should keep the stored initialization segment when a dead producer is replaced")
  void shouldKeepStoredInitializationSegmentWhenDeadProducerIsReplaced() throws Exception {
    var session = startedSession(remuxDecision());
    var sessionId = session.getSessionId();
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                segmentStore.segmentExists(sessionId, "init.mp4")
                    && segmentStore.segmentExists(sessionId, "segment0.m4s"));
    var storedInitialization = segmentStore.readSegment(sessionId, "init.mp4");

    killProducerAndDropSegmentsFrom(session, 1);

    var delivery = coordinator.deliver(sessionId, StreamSession.defaultVariant(), "segment1.m4s");

    assertThat(delivery)
        .as("the replacement attempt's initialization segment matched the stored one")
        .isInstanceOf(SegmentDelivery.Ready.class);
    assertThat(segmentStore.readSegment(sessionId, "init.mp4")).isEqualTo(storedInitialization);
    var recoveredMedia = decodableMediaFile(sessionId, "segment1.m4s");

    assertThat(workerFixture.worker().decodedVideoFrameCount(recoveredMedia))
        .as("recovered initialization and media fragment must decode together")
        .isPositive();
  }

  private StreamSession startedSession(TranscodeDecision decision) {
    var session =
        defaultSessionBuilder()
            .sourcePath(testVideo)
            .mediaProbe(defaultProbeBuilder().duration(Duration.ofSeconds(10)).build())
            .transcodeDecision(decision)
            .build();
    runtimeRegistry.save(session);
    lifecycle.startAll(session, 0, 0);
    return session;
  }

  /**
   * Forces the recovery precondition deterministically: the producer is dead (killed if still
   * alive, completed otherwise) and the advertised segments from {@code firstMissingIndex} on are
   * absent — the same observable state as a mid-stream crash.
   */
  private void killProducerAndDropSegmentsFrom(StreamSession session, int firstMissingIndex)
      throws Exception {
    workerFixture.worker().killProducer(session.getHandle().orElseThrow().attemptId());
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () ->
                !transcodeExecutor.isRunning(
                    session.getSessionId(), StreamSession.defaultVariant()));

    var outputDir = segmentStore.getOutputDirectory(session.getSessionId());
    for (var index = firstMissingIndex; index < 16; index++) {
      Files.deleteIfExists(outputDir.resolve(SegmentNames.mediaSegmentName(index)));
    }
  }

  private List<Double> packetTimestamps(UUID sessionId, String segmentName) throws Exception {
    return workerFixture.worker().packetTimestamps(decodableMediaFile(sessionId, segmentName));
  }

  private Path decodableMediaFile(UUID sessionId, String segmentName) throws Exception {
    var media = temporaryDirectory.resolve("decodable-" + segmentName + ".mp4");
    return Files.write(
        media, Fmp4Fixture.withInitializationSegment(segmentStore, sessionId, segmentName));
  }
}
