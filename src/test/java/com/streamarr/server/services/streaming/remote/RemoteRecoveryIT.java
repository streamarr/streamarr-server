package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fixtures.Fmp4Fixture;
import com.streamarr.server.fixtures.RecordedStream;
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
import java.util.List;
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
              .ffmpegScript(WorkerContainerFixture.emitRecordedStream(RecordedStream.START_AT_ZERO))
              .build()) {
        healthyWorker.start();
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> !server.eligibleWorkers(SOURCE_NAMESPACE_ID).isEmpty());

        var delivery =
            rig.coordinator()
                .deliver(streamSessionId, StreamSession.defaultVariant(), "segment0.m4s");

        assertThat(delivery)
            .isInstanceOfSatisfying(
                SegmentDelivery.Ready.class,
                ready ->
                    assertThat(RecordedStream.START_AT_ZERO.bytes())
                        .startsWith(
                            Fmp4Fixture.withInitializationSegment(
                                segmentStore, streamSessionId, ready.data())));
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
                .ffmpegScript(killableThenReplacedScript(RecordedStream.SEEK_TO_SIX_SECONDS))
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
                  .build());
      startThenKillInitialAttempt(rig, List.of(worker));

      var delivery =
          rig.coordinator()
              .deliver(streamSessionId, StreamSession.defaultVariant(), "segment1.m4s");

      assertThat(delivery)
          .isInstanceOfSatisfying(
              SegmentDelivery.Ready.class,
              ready ->
                  assertThat(
                          Fmp4Fixture.withInitializationSegment(
                              segmentStore, streamSessionId, ready.data()))
                      .as("the stored initialization segment and the replacement's segment 1")
                      .isEqualTo(RecordedStream.SEEK_TO_SIX_SECONDS.bytes()));
      assertInitialAttemptsSegmentsStored(segmentStore, streamSessionId);
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
    var script =
        killableThenReplacedScript(
            RecordedStream.SEEK_TO_SIX_SECONDS_WITH_DIFFERING_INITIALIZATION);

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
                  .build());
      startThenKillInitialAttempt(rig, List.of(firstWorker, secondWorker));

      var delivery =
          rig.coordinator()
              .deliver(streamSessionId, StreamSession.defaultVariant(), "segment1.m4s");

      assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(initializationSegmentMismatches(meterRegistry))
          .as("each eligible worker ran one replacement attempt and was refused")
          .isEqualTo(2);
      assertInitialAttemptsSegmentsStored(segmentStore, streamSessionId);
      assertThat(segmentStore.segmentExists(streamSessionId, "segment1.m4s")).isFalse();
      assertThat(rig.session().getHandle().orElseThrow().status())
          .isEqualTo(TranscodeStatus.FAILED);
    }
  }

  /**
   * The initial attempt writes the whole start-at-zero stream, so the worker closes and delivers
   * segment 0, then leaves a box header unfinished and stays alive until the test kills it: its
   * output ends inside a box, so it fails without delivering segment 1. A replacement attempt,
   * which seeks to segment 1, writes the given recording and exits cleanly.
   */
  private static String killableThenReplacedScript(RecordedStream replacement) {
    return """
        seek=0
        previous=
        for option in "$@"; do
          if [[ $previous == -ss ]]; then
            seek=$option
          fi
          previous=$option
        done
        if [[ $seek == 0 ]]; then
          cat %s
          printf 'moo'
          exec sleep 300
        fi
        %s
        """
        .formatted(
            RecordedStream.START_AT_ZERO.containerPath(),
            WorkerContainerFixture.emitRecordedStream(replacement));
  }

  private static void assertInitialAttemptsSegmentsStored(
      LocalSegmentStore segmentStore, UUID streamSessionId) {
    assertThat(RecordedStream.START_AT_ZERO.bytes())
        .as("the initial attempt's initialization segment and segment 0 stay stored")
        .startsWith(
            Fmp4Fixture.withInitializationSegment(
                segmentStore,
                streamSessionId,
                segmentStore.readSegment(streamSessionId, "segment0.m4s")));
  }

  private void startThenKillInitialAttempt(RecoveryRig rig, List<WorkerContainerFixture> workers)
      throws Exception {
    var configuration = rig.configuration();
    var streamSessionId = configuration.streamSessionId();
    var handle =
        configuration
            .executor()
            .start(transcodeRequest(streamSessionId, configuration.mediaFile()));
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
      RemoteTranscodeExecutor executor) {}

  private RecoveryRig recoveryRig(RecoveryRigConfiguration configuration) {
    var session =
        StreamSession.builder()
            .sessionId(configuration.streamSessionId())
            .mediaFileId(UUID.randomUUID())
            .authority(playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(configuration.mediaFile())
            .mediaProbe(defaultProbeBuilder().build())
            .transcodeDecision(transcodeDecision())
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

  private static TranscodeRequest transcodeRequest(UUID streamSessionId, Path mediaFile) {
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .targetSegmentDuration(6)
        .framerate(OptionalDouble.of(23.976))
        .transcodeDecision(transcodeDecision())
        .width(1920)
        .height(1080)
        .bitrate(5_000_000)
        .variantLabel(StreamSession.defaultVariant())
        .build();
  }

  private static TranscodeDecision transcodeDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.REMUX)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.copy("aac", 2, 128_000))
        .subtitleDecision(SubtitleDecision.exclude())
        .needsKeyframeAlignment(true)
        .build();
  }
}
