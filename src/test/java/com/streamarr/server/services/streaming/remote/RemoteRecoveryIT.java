package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.fixtures.WorkerContainerFixture;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance proof for ADR 0019's distributed recovery: a variant whose worker attempt fails is
 * re-dispatched, on the next request that needs it, to another live worker connection; only when
 * every eligible connection has been tried does the failure surface as a terminal outcome.
 */
@Tag("IntegrationTest")
@DisplayName("Remote Recovery Integration Tests")
class RemoteRecoveryIT {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
  private static final byte[] STORED_INITIALIZATION = "ftyp moov from encoder A".getBytes();
  private static final byte[] FIRST_MEDIA_SEGMENT = "moof mdat of segment 0".getBytes();
  private static final byte[] SECOND_MEDIA_SEGMENT = "moof mdat of segment 1".getBytes();

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should re-dispatch a failed variant to another live worker connection when recovering a producer")
  void shouldRedispatchFailedVariantToAnotherLiveWorkerConnectionWhenRecoveringProducer()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();
    var segmentData = "recovered remote segment".getBytes();

    try (var server = server(segmentStore)) {
      server.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var rig =
          recoveryRig(
              RecoveryRigConfiguration.builder()
                  .streamSessionId(streamSessionId)
                  .mediaFile(mediaFile)
                  .segmentStore(segmentStore)
                  .executor(executor)
                  .build());

      try (var failingWorker = workerBuilder(server, mediaRoot).ffmpegScript("exit 73\n").build()) {
        failingWorker.start();
        var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
        rig.session().setHandle(handle);
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> !server.isRunning(streamSessionId, StreamSession.defaultVariant()));
      }

      try (var healthyWorker =
          workerBuilder(server, mediaRoot)
              .ffmpegScript(
                  WorkerContainerFixture.emitSegments(
                      Map.of("init.mp4", STORED_INITIALIZATION, "segment0.m4s", segmentData)))
              .build()) {
        healthyWorker.start();
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> !server.eligibleWorkers(SOURCE_NAMESPACE_ID).isEmpty());

        var delivery =
            rig.coordinator()
                .deliver(streamSessionId, StreamSession.defaultVariant(), "segment0.m4s");

        assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
        assertThat(((SegmentDelivery.Ready) delivery).data()).isEqualTo(segmentData);
        assertThat(rig.session().getHandle().orElseThrow().status())
            .isEqualTo(TranscodeStatus.ACTIVE);
      }
    }
  }

  @Test
  @DisplayName("Should surface a terminal outcome when every worker attempt fails")
  void shouldSurfaceTerminalOutcomeWhenEveryWorkerAttemptFails() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var failingWorker = workerBuilder(server, mediaRoot).ffmpegScript("exit 73\n").build()) {
      server.start();
      failingWorker.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var rig =
          recoveryRig(
              RecoveryRigConfiguration.builder()
                  .streamSessionId(streamSessionId)
                  .mediaFile(mediaFile)
                  .segmentStore(segmentStore)
                  .executor(executor)
                  .build());

      var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
      rig.session().setHandle(handle);
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> !server.isRunning(streamSessionId, StreamSession.defaultVariant()));

      var delivery =
          rig.coordinator()
              .deliver(streamSessionId, StreamSession.defaultVariant(), "segment0.m4s");

      assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(rig.session().getHandle().orElseThrow().status())
          .isEqualTo(TranscodeStatus.FAILED);
    }
  }

  @Test
  @DisplayName(
      "Should publish a replacement attempt's media segments when its initialization segment matches the stored one")
  void shouldPublishReplacementAttemptsMediaSegmentsWhenItsInitializationSegmentMatchesStoredOne()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var meterRegistry = new SimpleMeterRegistry();
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore, meterRegistry);
        var worker =
            workerBuilder(server, mediaRoot)
                .ffmpegScript(killableThenReplacedScript(STORED_INITIALIZATION))
                .build()) {
      server.start();
      worker.start();
      var rig =
          recoveryRig(
              RecoveryRigConfiguration.builder()
                  .streamSessionId(streamSessionId)
                  .mediaFile(mediaFile)
                  .segmentStore(segmentStore)
                  .executor(new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot))
                  .transcodeDecision(fragmentedTranscodeDecision())
                  .build());
      startThenKillInitialAttempt(rig, List.of(worker));

      var delivery =
          rig.coordinator()
              .deliver(streamSessionId, StreamSession.defaultVariant(), "segment1.m4s");

      assertThat(delivery).isEqualTo(new SegmentDelivery.Ready(SECOND_MEDIA_SEGMENT));
      assertThat(segmentStore.readSegment(streamSessionId, "init.mp4"))
          .isEqualTo(STORED_INITIALIZATION);
      assertThat(segmentStore.readSegment(streamSessionId, "segment0.m4s"))
          .isEqualTo(FIRST_MEDIA_SEGMENT);
      assertThat(initializationSegmentMismatches(meterRegistry)).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should keep the stored segments and try every worker when each replacement attempt's initialization segment differs")
  void
      shouldKeepStoredSegmentsAndTryEveryWorkerWhenEachReplacementAttemptsInitializationSegmentDiffers()
          throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var meterRegistry = new SimpleMeterRegistry();
    var streamSessionId = UUID.randomUUID();
    var script = killableThenReplacedScript("ftyp moov from encoder B".getBytes());

    try (var server = server(segmentStore, meterRegistry);
        var firstWorker = workerBuilder(server, mediaRoot).ffmpegScript(script).build();
        var secondWorker = workerBuilder(server, mediaRoot).ffmpegScript(script).build()) {
      server.start();
      firstWorker.start();
      secondWorker.start();
      var rig =
          recoveryRig(
              RecoveryRigConfiguration.builder()
                  .streamSessionId(streamSessionId)
                  .mediaFile(mediaFile)
                  .segmentStore(segmentStore)
                  .executor(new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot))
                  .transcodeDecision(fragmentedTranscodeDecision())
                  .build());
      startThenKillInitialAttempt(rig, List.of(firstWorker, secondWorker));

      var delivery =
          rig.coordinator()
              .deliver(streamSessionId, StreamSession.defaultVariant(), "segment1.m4s");

      assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(initializationSegmentMismatches(meterRegistry))
          .as("each eligible worker ran one replacement attempt and was refused")
          .isEqualTo(2);
      assertThat(segmentStore.readSegment(streamSessionId, "init.mp4"))
          .isEqualTo(STORED_INITIALIZATION);
      assertThat(segmentStore.readSegment(streamSessionId, "segment0.m4s"))
          .isEqualTo(FIRST_MEDIA_SEGMENT);
      assertThat(segmentStore.segmentExists(streamSessionId, "segment1.m4s")).isFalse();
      assertThat(rig.session().getHandle().orElseThrow().status())
          .isEqualTo(TranscodeStatus.FAILED);
    }
  }

  private static String killableThenReplacedScript(byte[] replacementInitialization) {
    var initial = new LinkedHashMap<String, byte[]>();
    initial.put("init.mp4", STORED_INITIALIZATION);
    initial.put("segment0.m4s", FIRST_MEDIA_SEGMENT);
    var replacement = new LinkedHashMap<String, byte[]>();
    replacement.put("init.mp4", replacementInitialization);
    replacement.put("segment1.m4s", SECOND_MEDIA_SEGMENT);
    // The initial attempt (start number 0) writes its segments in a subshell, which confines the
    // emitted script's exit, then stays alive until the test kills it. The replacement attempt
    // starts at segment 1.
    return """
        start=0
        previous=
        for option in "$@"; do
          if [[ $previous == -start_number ]]; then
            start=$option
          fi
          previous=$option
        done
        if [[ $start == 0 ]]; then
          (
        %s
          )
          exec sleep 300
        fi
        %s
        """
        .formatted(
            WorkerContainerFixture.emitSegments(initial),
            WorkerContainerFixture.emitSegments(replacement));
  }

  private void startThenKillInitialAttempt(RecoveryRig rig, List<WorkerContainerFixture> workers)
      throws Exception {
    var configuration = rig.configuration();
    var streamSessionId = configuration.streamSessionId();
    var handle =
        configuration
            .executor()
            .start(
                transcodeRequest(
                    streamSessionId, configuration.mediaFile(), configuration.transcodeDecision()));
    rig.session().setHandle(handle);
    await()
        .atMost(30, TimeUnit.SECONDS)
        .until(() -> configuration.segmentStore().segmentExists(streamSessionId, "segment0.m4s"));
    for (var worker : workers) {
      if (worker.commandFor(handle.attemptId()).isPresent()) {
        worker.killProducer(handle.attemptId());
      }
    }

    await()
        .atMost(10, TimeUnit.SECONDS)
        .until(
            () ->
                !configuration
                    .executor()
                    .isRunning(streamSessionId, StreamSession.defaultVariant()));
  }

  private static double initializationSegmentMismatches(MeterRegistry meterRegistry) {
    return InitializationSegmentMismatchMetric.count(meterRegistry);
  }

  private record RecoveryRig(
      SegmentDeliveryCoordinator coordinator,
      StreamSession session,
      RecoveryRigConfiguration configuration) {}

  @Builder
  private record RecoveryRigConfiguration(
      UUID streamSessionId,
      Path mediaFile,
      LocalSegmentStore segmentStore,
      RemoteTranscodeExecutor executor,
      TranscodeDecision transcodeDecision) {

    private RecoveryRigConfiguration {
      transcodeDecision =
          transcodeDecision != null ? transcodeDecision : RemoteRecoveryIT.transcodeDecision();
    }
  }

  private RecoveryRig recoveryRig(RecoveryRigConfiguration configuration) {
    var session =
        StreamSession.builder()
            .sessionId(configuration.streamSessionId())
            .mediaFileId(UUID.randomUUID())
            .authority(playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(configuration.mediaFile())
            .mediaProbe(defaultProbeBuilder().build())
            .transcodeDecision(configuration.transcodeDecision())
            .build();
    var registry = new FakeRuntimeStreamSessionRegistry();
    registry.save(session);
    var properties =
        StreamingProperties.builder()
            .targetSegmentDuration(Duration.ofSeconds(6))
            .producerStallThreshold(Duration.ofSeconds(3))
            .build();
    var rig =
        StreamingRigFixture.streamingRigBuilder()
            .segmentStore(configuration.segmentStore())
            .transcodeExecutor(configuration.executor())
            .properties(properties)
            .runtimeRegistry(registry)
            .pollInterval(Duration.ofMillis(50))
            .build();
    return new RecoveryRig(rig.coordinator(), session, configuration);
  }

  private WorkerSessionServer server(LocalSegmentStore segmentStore) {
    return server(segmentStore, new SimpleMeterRegistry());
  }

  private WorkerSessionServer server(LocalSegmentStore segmentStore, MeterRegistry meterRegistry) {
    return new WorkerSessionServer(
        serverConfigurationBuilder().address("127.0.0.1").build(), segmentStore, meterRegistry);
  }

  private WorkerContainerFixture.WorkerContainerFixtureBuilder workerBuilder(
      WorkerSessionServer server, Path mediaRoot) {
    return WorkerContainerFixture.builder()
        .workerSessions(server)
        .sourceNamespaceId(SOURCE_NAMESPACE_ID)
        .sourceRoot(mediaRoot);
  }

  private TranscodeRequest transcodeRequest(UUID streamSessionId, Path mediaFile) {
    return transcodeRequest(streamSessionId, mediaFile, transcodeDecision());
  }

  private static TranscodeRequest transcodeRequest(
      UUID streamSessionId, Path mediaFile, TranscodeDecision transcodeDecision) {
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .targetSegmentDuration(6)
        .framerate(OptionalDouble.of(23.976))
        .transcodeDecision(transcodeDecision)
        .width(1920)
        .height(1080)
        .bitrate(5_000_000)
        .variantLabel(StreamSession.defaultVariant())
        .build();
  }

  private static TranscodeDecision fragmentedTranscodeDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.REMUX)
        .videoCodecFamily("hevc")
        .audioDecision(AudioDecision.copy("aac", 2, 128_000))
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(ContainerFormat.FMP4)
        .needsKeyframeAlignment(true)
        .build();
  }

  private static TranscodeDecision transcodeDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.REMUX)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.copy("aac", 2, 128_000))
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(ContainerFormat.FMP4)
        .needsKeyframeAlignment(true)
        .build();
  }
}
