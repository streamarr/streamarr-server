package com.streamarr.server.services.streaming;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.remuxMpegtsDecision;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.RemoteTranscodeExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real-FFmpeg proof of ADR 0019's recovery contract: after a producer dies mid-stream, the next
 * request replaces it at the requested segment's offset and the replacement's timestamps continue
 * the absolute timeline. MPEG-TS continuity-counter preservation across a replacement producer is
 * deliberately NOT asserted — the CC reset is a documented ADR 0019 deviation (following Jellyfin);
 * PTS/DTS continuity comes from {@code -copyts} + input {@code -ss} + {@code -start_number}.
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
  @DisplayName("Should continue the absolute timeline when a dead MPEG-TS producer is replaced")
  void shouldContinueAbsoluteTimelineWhenDeadMpegtsProducerIsReplaced() throws Exception {
    var session = startedSession(remuxMpegtsDecision());
    assertThat(session.getHandle().orElseThrow().processId()).isEmpty();
    var sessionId = session.getSessionId();
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                segmentStore.segmentExists(sessionId, "segment0.ts")
                    && segmentStore.segmentExists(sessionId, "segment1.ts"));

    killProducerAndDropSegmentsFrom(session, 2, ".ts");

    var delivery = coordinator.deliver(sessionId, StreamSession.defaultVariant(), "segment2.ts");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    var replacementCommand =
        workerFixture
            .worker()
            .commandFor(session.getHandle().orElseThrow().attemptId())
            .orElseThrow();
    assertThat(replacementCommand)
        .containsSubsequence("-ss", String.valueOf(2 * SEGMENT_DURATION_SECONDS))
        .containsSubsequence("-start_number", "2");

    var outputDir = segmentStore.getOutputDirectory(sessionId);
    // The MPEG-TS muxer applies a constant output offset (from -max_delay) to every run; the
    // continuity contract is measured against the first run's timeline, not absolute zero.
    var timelineOffset =
        workerFixture.worker().packetTimestamps(outputDir.resolve("segment0.ts")).getFirst();
    var lastPtsBeforeDeath =
        workerFixture.worker().packetTimestamps(outputDir.resolve("segment1.ts")).getLast();
    var replacementPts = workerFixture.worker().packetTimestamps(outputDir.resolve("segment2.ts"));
    assertThat(replacementPts.getFirst())
        .isCloseTo(timelineOffset + 2.0 * SEGMENT_DURATION_SECONDS, offset(0.5));
    assertThat(replacementPts.getFirst()).isGreaterThanOrEqualTo(lastPtsBeforeDeath - 0.1);

    // The replacement emits a contiguous run from the requested index, never a lone segment.
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(() -> segmentStore.segmentExists(sessionId, "segment3.ts"));
  }

  @Test
  @DisplayName("Should rewrite the init segment when a dead fMP4 producer is replaced")
  void shouldRewriteInitSegmentWhenDeadFmp4ProducerIsReplaced() throws Exception {
    var session = startedSession(remuxFmp4Decision());
    var sessionId = session.getSessionId();
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(
            () ->
                segmentStore.segmentExists(sessionId, "init.mp4")
                    && segmentStore.segmentExists(sessionId, "segment0.m4s"));

    killProducerAndDropSegmentsFrom(session, 1, ".m4s");
    Files.delete(segmentStore.getOutputDirectory(sessionId).resolve("init.mp4"));

    var delivery = coordinator.deliver(sessionId, StreamSession.defaultVariant(), "segment1.m4s");

    assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
    var replacementCommand =
        workerFixture
            .worker()
            .commandFor(session.getHandle().orElseThrow().attemptId())
            .orElseThrow();
    assertThat(replacementCommand)
        .containsSubsequence("-ss", String.valueOf(SEGMENT_DURATION_SECONDS))
        .containsSubsequence("-start_number", "1")
        .containsSubsequence("-hls_fmp4_init_filename", "init.mp4")
        .anyMatch(argument -> argument.contains("frag_discont"));
    assertThat(segmentStore.segmentExists(sessionId, "init.mp4")).isTrue();
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
  private void killProducerAndDropSegmentsFrom(
      StreamSession session, int firstMissingIndex, String extension) throws Exception {
    workerFixture.worker().killProducer(session.getHandle().orElseThrow().attemptId());
    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () ->
                !transcodeExecutor.isRunning(
                    session.getSessionId(), StreamSession.defaultVariant()));

    var outputDir = segmentStore.getOutputDirectory(session.getSessionId());
    for (var index = firstMissingIndex; index < 16; index++) {
      Files.deleteIfExists(outputDir.resolve("segment" + index + extension));
    }
  }

  private static TranscodeDecision remuxFmp4Decision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.REMUX)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.copy("aac", 2, 0))
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(ContainerFormat.FMP4)
        .needsKeyframeAlignment(true)
        .build();
  }
}
