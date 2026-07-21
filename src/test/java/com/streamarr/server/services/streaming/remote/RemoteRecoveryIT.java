package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.playbackAuthorityFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.ContainerFormat;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.domain.streaming.TranscodeStatus;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentProducingFfmpegProcessManager;
import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.streaming.ProducerLifecycleService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.SegmentDeliveryCoordinator;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.worker.TranscodeWorker;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
class RemoteRecoveryIT extends AbstractIntegrationTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should re-dispatch a failed variant to another live worker connection")
  void shouldRedispatchFailedVariantToAnotherLiveWorkerConnection() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var mediaFile = Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();
    var segmentData = "recovered remote segment".getBytes();

    try (var server = server(segmentStore)) {
      server.start();
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var rig = recoveryRig(streamSessionId, mediaFile, segmentStore, executor);

      try (var failingWorker = worker(mediaRoot, new FailingFfmpegProcessManager())) {
        failingWorker.start("localhost", server.port());
        var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
        rig.session().setHandle(handle);
        await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId));
      }

      try (var healthyWorker =
          worker(
              mediaRoot,
              new FakeSegmentProducingFfmpegProcessManager(Map.of("segment0.ts", segmentData)))) {
        healthyWorker.start("localhost", server.port());
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> !server.eligibleWorkers(SOURCE_NAMESPACE_ID).isEmpty());

        var delivery =
            rig.coordinator()
                .deliver(streamSessionId, StreamSession.defaultVariant(), "segment0.ts");

        assertThat(delivery).isInstanceOf(SegmentDelivery.Ready.class);
        assertThat(((SegmentDelivery.Ready) delivery).data()).isEqualTo(segmentData);
        assertThat(rig.session().getHandle().status()).isEqualTo(TranscodeStatus.ACTIVE);
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
        var failingWorker = worker(mediaRoot, new FailingFfmpegProcessManager())) {
      server.start();
      failingWorker.start("localhost", server.port());
      var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, mediaRoot);
      var rig = recoveryRig(streamSessionId, mediaFile, segmentStore, executor);

      var handle = executor.start(transcodeRequest(streamSessionId, mediaFile));
      rig.session().setHandle(handle);
      await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId));

      var delivery =
          rig.coordinator().deliver(streamSessionId, StreamSession.defaultVariant(), "segment0.ts");

      assertThat(delivery).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(rig.session().getHandle().status()).isEqualTo(TranscodeStatus.FAILED);
    }
  }

  private record RecoveryRig(SegmentDeliveryCoordinator coordinator, StreamSession session) {}

  private RecoveryRig recoveryRig(
      UUID streamSessionId,
      Path mediaFile,
      LocalSegmentStore segmentStore,
      RemoteTranscodeExecutor executor) {
    var session =
        StreamSession.builder()
            .sessionId(streamSessionId)
            .authority(playbackAuthorityFor(UUID.randomUUID()))
            .sourcePath(mediaFile)
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
    var lifecycle =
        ProducerLifecycleService.builder()
            .transcodeExecutor(executor)
            .segmentStore(segmentStore)
            .properties(properties)
            .runtimeRegistry(registry)
            .sessionMutex(new MutexFactory<>())
            .build();
    var coordinator =
        SegmentDeliveryCoordinator.builder()
            .runtimeRegistry(registry)
            .segmentStore(segmentStore)
            .transcodeExecutor(executor)
            .producerLifecycle(lifecycle)
            .properties(properties)
            .clock(Clock.systemUTC())
            .pollInterval(Duration.ofMillis(50))
            .build();
    return new RecoveryRig(coordinator, session);
  }

  private WorkerSessionServer server(LocalSegmentStore segmentStore) throws URISyntaxException {
    var configuration =
        WorkerSessionServerConfiguration.builder()
            .port(0)
            .trustDomain("streamarr.test")
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("server-cert.pem"))
                    .privateKey(resource("server-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .build();
    return new WorkerSessionServer(configuration, segmentStore);
  }

  private TranscodeWorker worker(Path mediaRoot, FakeFfmpegProcessManager processManager)
      throws URISyntaxException {
    var configuration =
        TranscodeWorkerConfiguration.builder()
            .workerId(WORKER_ID)
            .bootId(UUID.randomUUID())
            .availableSlots(1)
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("worker-cert.pem"))
                    .privateKey(resource("worker-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .segmentBasePath(tempDir.resolve("worker-segments"))
            .build();
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"),
            processManager,
            new TranscodeCapabilityService(
                "ffmpeg",
                _ -> {
                  throw new IllegalStateException("Not used for remux");
                }));
    return new TranscodeWorker(configuration, engine);
  }

  private TranscodeRequest transcodeRequest(UUID streamSessionId, Path mediaFile) {
    return TranscodeRequest.builder()
        .sessionId(streamSessionId)
        .sourcePath(mediaFile)
        .targetSegmentDuration(6)
        .framerate(23.976)
        .transcodeDecision(transcodeDecision())
        .width(1920)
        .height(1080)
        .bitrate(5_000_000)
        .variantLabel(StreamSession.defaultVariant())
        .build();
  }

  private TranscodeDecision transcodeDecision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.REMUX)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.copy("aac", 2, 128_000))
        .subtitleDecision(SubtitleDecision.exclude())
        .containerFormat(ContainerFormat.MPEGTS)
        .needsKeyframeAlignment(true)
        .build();
  }

  private Path resource(String name) throws URISyntaxException {
    var url = Objects.requireNonNull(getClass().getResource("/tls/" + name));
    return Path.of(url.toURI());
  }

  private static final class FailingFfmpegProcessManager extends FakeFfmpegProcessManager {

    @Override
    public Process startProcess(
        UUID sessionId, String variantLabel, List<String> command, Path workingDirectory) {
      throw new IllegalStateException("Simulated worker transcode startup failure");
    }
  }
}
