package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.StreamarrServerApplication;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentProducingFfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.server.services.streaming.remote.WorkerSessionServer;
import com.streamarr.server.services.streaming.remote.WorkerSessionServerConfiguration;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.v1.AudioDecision;
import com.streamarr.transcode.v1.AudioMode;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.RenditionJob;
import com.streamarr.transcode.v1.RenditionSpec;
import com.streamarr.transcode.v1.SubtitleDecision;
import com.streamarr.transcode.v1.SubtitleMode;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.Uuid;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Integration Tests")
@SpringBootTest(classes = StreamarrServerApplication.class)
class TranscodeWorkerIT extends AbstractIntegrationTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should run a dispatched rendition through the shared FFmpeg engine")
  void shouldRunDispatchedRenditionThroughSharedFfmpegEngine() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(server.dispatch(renditionJob(streamSessionId))).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStarted().contains(streamSessionId));
    }
  }

  @Test
  @DisplayName("Should stop its running rendition when the worker closes")
  void shouldStopRunningRenditionWhenWorkerCloses() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(renditionJob(streamSessionId))).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStarted().contains(streamSessionId));

      worker.close();

      assertThat(processManager.getStopped()).contains(streamSessionId);
    }
  }

  @Test
  @DisplayName("Should stop its running rendition when the control plane disconnects")
  void shouldStopRunningRenditionWhenControlPlaneDisconnects() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(renditionJob(streamSessionId))).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStarted().contains(streamSessionId));

      server.close();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStopped().contains(streamSessionId));
    }
  }

  @Test
  @DisplayName("Should stop the dispatched rendition requested by the control plane")
  void shouldStopDispatchedRenditionRequestedByControlPlane() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();
    var stoppedJob = renditionJob(streamSessionId, "720p");
    var survivingJob = renditionJob(streamSessionId, "1080p");

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(stoppedJob)).isTrue();
      assertThat(server.dispatch(survivingJob)).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(
              () ->
                  processManager.isRunning(streamSessionId, "720p")
                      && processManager.isRunning(streamSessionId, "1080p"));

      assertThat(server.stopRendition(uuid(stoppedJob.getJobAttemptId()))).isTrue();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> !processManager.isRunning(streamSessionId, "720p"));
      assertThat(processManager.isRunning(streamSessionId, "1080p")).isTrue();
    }
  }

  @Test
  @DisplayName("Should upload a produced segment through the worker connection")
  void shouldUploadProducedSegmentThroughWorkerConnection() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentData = "remote segment".getBytes();
    var processManager = new FakeSegmentProducingFfmpegProcessManager("segment0.ts", segmentData);
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();

    try (var server = server(segmentStore);
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(
              server.dispatch(
                  renditionJob(streamSessionId, "720p", ContainerFormat.CONTAINER_FORMAT_MPEG_TS)))
          .isTrue();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> segmentStore.segmentExists(streamSessionId, "720p/segment0.ts"));
      assertThat(segmentStore.readSegment(streamSessionId, "720p/segment0.ts"))
          .isEqualTo(segmentData);
    }
  }

  @Test
  @DisplayName("Should release worker capacity when a rendition fails to start")
  void shouldReleaseWorkerCapacityWhenRenditionFailsToStart() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new StartupFailingProcessManager();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(renditionJob(UUID.randomUUID()))).isTrue();
      assertThat(server.dispatch(renditionJob(UUID.randomUUID()))).isTrue();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> server.dispatch(renditionJob(UUID.randomUUID())));
    }
  }

  private WorkerSessionServer server() throws URISyntaxException {
    return server(new LocalSegmentStore(tempDir.resolve("server-segments")));
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

  private TranscodeWorker worker(FakeFfmpegProcessManager processManager, Path mediaRoot)
      throws URISyntaxException {
    var configuration =
        TranscodeWorkerConfiguration.builder()
            .workerId(WORKER_ID)
            .bootId(UUID.randomUUID())
            .availableSlots(2)
            .tlsIdentity(
                PemTlsIdentity.builder()
                    .certificate(resource("worker-cert.pem"))
                    .privateKey(resource("worker-key.fixture"))
                    .trustBundle(resource("ca-cert.pem"))
                    .build())
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .segmentBasePath(tempDir.resolve("segments"))
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

  private RenditionJob renditionJob(UUID streamSessionId) {
    return renditionJob(streamSessionId, "default", ContainerFormat.CONTAINER_FORMAT_FMP4);
  }

  private RenditionJob renditionJob(UUID streamSessionId, String renditionName) {
    return renditionJob(streamSessionId, renditionName, ContainerFormat.CONTAINER_FORMAT_FMP4);
  }

  private RenditionJob renditionJob(
      UUID streamSessionId, String renditionName, ContainerFormat containerFormat) {
    return RenditionJob.newBuilder()
        .setStreamSessionId(uuid(streamSessionId))
        .setJobId(uuid(UUID.randomUUID()))
        .setJobAttemptId(uuid(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(uuid(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .setDecision(
            TranscodeDecision.newBuilder()
                .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                .setVideoCodecFamily("h264")
                .setAudio(
                    AudioDecision.newBuilder()
                        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
                        .setCodec("aac")
                        .setChannels(2)
                        .setBitrateBitsPerSecond(128_000))
                .setSubtitle(
                    SubtitleDecision.newBuilder().setMode(SubtitleMode.SUBTITLE_MODE_EXCLUDE))
                .setContainer(containerFormat)
                .setAlignKeyframesToSegments(true))
        .setRendition(
            RenditionSpec.newBuilder()
                .setRenditionName(renditionName)
                .setWidth(1920)
                .setHeight(1080)
                .setBitrateBitsPerSecond(5_000_000))
        .setExecution(
            TranscodeExecution.newBuilder().setSegmentDurationSeconds(6).setFramerate(23.976))
        .build();
  }

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  private UUID uuid(Uuid value) {
    return new UUID(value.getMostSignificantBits(), value.getLeastSignificantBits());
  }

  private Path resource(String name) throws URISyntaxException {
    var url = Objects.requireNonNull(getClass().getResource("/tls/" + name));
    return Path.of(url.toURI());
  }

  private static final class StartupFailingProcessManager extends FakeFfmpegProcessManager {

    @Override
    public Process startProcess(
        UUID sessionId, String renditionName, List<String> command, Path workingDirectory) {
      throw new IllegalStateException("FFmpeg failed to start");
    }
  }
}
