package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.StreamarrServerApplication;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentProducingFfmpegProcessManager;
import com.streamarr.server.services.streaming.SegmentStore;
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
import com.streamarr.transcode.v1.SubtitleDecision;
import com.streamarr.transcode.v1.SubtitleMode;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
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
  @DisplayName("Should run a dispatched variant through the shared FFmpeg engine")
  void shouldRunDispatchedVariantThroughSharedFfmpegEngine() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(server.dispatch(variantJob(streamSessionId))).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStarted().contains(streamSessionId));
    }
  }

  @Test
  @DisplayName("Should reject duplicate worker startup")
  void shouldRejectDuplicateWorkerStartup() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));

    try (var server = server();
        var worker = worker(new FakeFfmpegProcessManager(), mediaRoot)) {
      server.start();
      var serverPort = server.port();
      worker.start("localhost", serverPort);

      assertThatThrownBy(() -> worker.start("localhost", serverPort))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Transcode worker is already started");
    }
  }

  @Test
  @DisplayName("Should stop its running variant when the worker closes")
  void shouldStopRunningVariantWhenWorkerCloses() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(variantJob(streamSessionId))).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStarted().contains(streamSessionId));

      worker.close();

      assertThat(processManager.getStopped()).contains(streamSessionId);
    }
  }

  @Test
  @DisplayName("Should stop its running variant when the control plane disconnects")
  void shouldStopRunningVariantWhenControlPlaneDisconnects() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(variantJob(streamSessionId))).isTrue();
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
  @DisplayName("Should report the control-plane disconnect cause to its process owner")
  void shouldReportControlPlaneDisconnectCauseToItsProcessOwner() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));

    try (var server = server();
        var worker = worker(new FakeFfmpegProcessManager(), mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      server.close();

      assertThatThrownBy(worker::awaitDisconnection)
          .isInstanceOf(WorkerJobException.class)
          .hasMessage("Worker session failed")
          .hasRootCauseInstanceOf(StatusRuntimeException.class);
    }
  }

  @Test
  @DisplayName("Should stop the dispatched variant requested by the control plane")
  void shouldStopDispatchedVariantRequestedByControlPlane() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();
    var stoppedJob = variantJob(streamSessionId, "720p");
    var survivingJob = variantJob(streamSessionId, "1080p");

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

      assertThat(server.stopVariant(uuid(stoppedJob.getJobAttemptId()))).isTrue();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> !processManager.isRunning(streamSessionId, "720p"));
      assertThat(processManager.isRunning(streamSessionId, "1080p")).isTrue();
    }
  }

  @Test
  @DisplayName("Should preserve control-plane order when replacing a variant")
  void shouldPreserveControlPlaneOrderWhenReplacingVariant() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();
    var streamSessionId = UUID.randomUUID();
    var currentJob = variantJob(streamSessionId, "720p");

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(currentJob)).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.isRunning(streamSessionId, "720p"));

      for (var replacement = 0; replacement < 100; replacement++) {
        assertThat(server.stopVariant(uuid(currentJob.getJobAttemptId()))).isTrue();
        currentJob = variantJob(streamSessionId, "720p");
        assertThat(server.dispatch(currentJob)).isTrue();
      }

      await()
          .atMost(10, TimeUnit.SECONDS)
          .during(Duration.ofSeconds(1))
          .until(() -> processManager.isRunning(streamSessionId, "720p"));
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
    var job = variantJob(streamSessionId, "720p", ContainerFormat.CONTAINER_FORMAT_MPEG_TS);

    try (var server = server(segmentStore);
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(server.dispatch(job)).isTrue();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> segmentStore.segmentExists(streamSessionId, "720p/segment0.ts"));
      assertThat(segmentStore.readSegment(streamSessionId, "720p/segment0.ts"))
          .isEqualTo(segmentData);
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(
              () ->
                  Files.notExists(
                      tempDir
                          .resolve("segments")
                          .resolve(uuid(job.getJobAttemptId()).toString())
                          .resolve("segment0.ts")));
    }
  }

  @Test
  @DisplayName("Should complete a variant after uploading all produced segments")
  void shouldCompleteVariantAfterUploadingAllProducedSegments() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var segmentStore = new LocalSegmentStore(tempDir.resolve("server-segments"));
    var streamSessionId = UUID.randomUUID();
    var processManager =
        new EndingFfmpegProcessManager(Map.of("segment0.ts", "complete segment".getBytes()));
    var job = variantJob(streamSessionId, "720p", ContainerFormat.CONTAINER_FORMAT_MPEG_TS);

    try (var server = server(segmentStore);
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(server.dispatch(job)).isTrue();
      assertThat(server.isRunning(streamSessionId)).isTrue();

      await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId));
      assertThat(segmentStore.readSegment(streamSessionId, "720p/segment0.ts"))
          .isEqualTo("complete segment".getBytes());
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(
              () ->
                  Files.notExists(
                      tempDir.resolve("segments").resolve(uuid(job.getJobAttemptId()).toString())));
    }
  }

  @Test
  @DisplayName("Should stop a variant when the control plane rejects segment storage")
  void shouldStopVariantWhenControlPlaneRejectsSegmentStorage() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var streamSessionId = UUID.randomUUID();
    var processManager =
        new FakeSegmentProducingFfmpegProcessManager("segment0.ts", "segment".getBytes());
    var job = variantJob(streamSessionId, "720p", ContainerFormat.CONTAINER_FORMAT_MPEG_TS);

    try (var server = server(new FailingSegmentStore(tempDir.resolve("server-segments")));
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(server.dispatch(job)).isTrue();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStopped().contains(streamSessionId));
      await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId));
      assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("Should fail a variant when FFmpeg exits without producing media")
  void shouldFailVariantWhenFfmpegExitsWithoutProducingMedia() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var streamSessionId = UUID.randomUUID();
    var processManager = new EndingFfmpegProcessManager(Map.of());

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      assertThat(
              server.dispatch(
                  variantJob(streamSessionId, "720p", ContainerFormat.CONTAINER_FORMAT_MPEG_TS)))
          .isTrue();
      assertThat(server.isRunning(streamSessionId)).isTrue();

      await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId));
    }
  }

  @Test
  @DisplayName("Should release worker capacity when a variant fails to start")
  void shouldReleaseWorkerCapacityWhenVariantFailsToStart() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new StartupFailingProcessManager();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      assertThat(server.dispatch(variantJob(UUID.randomUUID()))).isTrue();
      assertThat(server.dispatch(variantJob(UUID.randomUUID()))).isTrue();

      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> server.dispatch(variantJob(UUID.randomUUID())));
    }
  }

  @Test
  @DisplayName("Should reject malformed variant decisions without poisoning worker capacity")
  void shouldRejectMalformedVariantDecisionsWithoutPoisoningWorkerCapacity() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager = new FakeFfmpegProcessManager();

    try (var server = server();
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      for (var malformedJob : malformedVariantJobs()) {
        assertThat(server.dispatch(malformedJob)).isTrue();
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> server.availableSlots(SOURCE_NAMESPACE_ID) == 2);
        assertThat(processManager.getStarted())
            .doesNotContain(uuid(malformedJob.getStreamSessionId()));
      }

      var validJob = variantJob(UUID.randomUUID());
      assertThat(server.dispatch(validJob)).isTrue();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> processManager.getStarted().contains(uuid(validJob.getStreamSessionId())));
    }
  }

  private WorkerSessionServer server() throws URISyntaxException {
    return server(new LocalSegmentStore(tempDir.resolve("server-segments")));
  }

  private WorkerSessionServer server(SegmentStore segmentStore) throws URISyntaxException {
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

  private VariantJob variantJob(UUID streamSessionId) {
    return variantJob(streamSessionId, "default", ContainerFormat.CONTAINER_FORMAT_FMP4);
  }

  private VariantJob variantJob(UUID streamSessionId, String variantLabel) {
    return variantJob(streamSessionId, variantLabel, ContainerFormat.CONTAINER_FORMAT_FMP4);
  }

  private VariantJob variantJob(
      UUID streamSessionId, String variantLabel, ContainerFormat containerFormat) {
    return VariantJob.newBuilder()
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
        .setVariant(
            VariantSpec.newBuilder()
                .setVariantLabel(variantLabel)
                .setWidth(1920)
                .setHeight(1080)
                .setBitrateBitsPerSecond(5_000_000))
        .setExecution(
            TranscodeExecution.newBuilder().setTargetSegmentDurationSeconds(6).setFramerate(23.976))
        .build();
  }

  private List<VariantJob> malformedVariantJobs() {
    var unspecifiedMode = variantJob(UUID.randomUUID());
    var unknownMode = variantJob(UUID.randomUUID());
    var unspecifiedAudio = variantJob(UUID.randomUUID());
    var unknownAudio = variantJob(UUID.randomUUID());
    var unspecifiedSubtitle = variantJob(UUID.randomUUID());
    var unknownSubtitle = variantJob(UUID.randomUUID());
    var unspecifiedContainer = variantJob(UUID.randomUUID());
    var unknownContainer = variantJob(UUID.randomUUID());
    return List.of(
        unspecifiedMode.toBuilder()
            .setDecision(
                unspecifiedMode.getDecision().toBuilder()
                    .setMode(TranscodeMode.TRANSCODE_MODE_UNSPECIFIED))
            .build(),
        unknownMode.toBuilder()
            .setDecision(unknownMode.getDecision().toBuilder().setModeValue(Integer.MAX_VALUE))
            .build(),
        unspecifiedAudio.toBuilder()
            .setDecision(
                unspecifiedAudio.getDecision().toBuilder()
                    .setAudio(
                        unspecifiedAudio.getDecision().getAudio().toBuilder()
                            .setMode(AudioMode.AUDIO_MODE_UNSPECIFIED)))
            .build(),
        unknownAudio.toBuilder()
            .setDecision(
                unknownAudio.getDecision().toBuilder()
                    .setAudio(
                        unknownAudio.getDecision().getAudio().toBuilder()
                            .setModeValue(Integer.MAX_VALUE)))
            .build(),
        unspecifiedSubtitle.toBuilder()
            .setDecision(
                unspecifiedSubtitle.getDecision().toBuilder()
                    .setSubtitle(
                        unspecifiedSubtitle.getDecision().getSubtitle().toBuilder()
                            .setMode(SubtitleMode.SUBTITLE_MODE_UNSPECIFIED)))
            .build(),
        unknownSubtitle.toBuilder()
            .setDecision(
                unknownSubtitle.getDecision().toBuilder()
                    .setSubtitle(
                        unknownSubtitle.getDecision().getSubtitle().toBuilder()
                            .setModeValue(Integer.MAX_VALUE)))
            .build(),
        unspecifiedContainer.toBuilder()
            .setDecision(
                unspecifiedContainer.getDecision().toBuilder()
                    .setContainer(ContainerFormat.CONTAINER_FORMAT_UNSPECIFIED))
            .build(),
        unknownContainer.toBuilder()
            .setDecision(
                unknownContainer.getDecision().toBuilder().setContainerValue(Integer.MAX_VALUE))
            .build());
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
        UUID sessionId, String variantLabel, List<String> command, Path workingDirectory) {
      throw new IllegalStateException("FFmpeg failed to start");
    }
  }

  private static final class FailingSegmentStore extends LocalSegmentStore {

    private FailingSegmentStore(Path baseDir) {
      super(baseDir);
    }

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      throw new IllegalStateException("Storage unavailable");
    }
  }

  private static final class EndingFfmpegProcessManager extends FakeFfmpegProcessManager {

    private final Map<String, byte[]> segments;

    private EndingFfmpegProcessManager(Map<String, byte[]> segments) {
      this.segments = segments;
    }

    @Override
    public Process startProcess(
        UUID sessionId, String variantLabel, List<String> command, Path workingDirectory) {
      var process = super.startProcess(sessionId, variantLabel, command, workingDirectory);
      try {
        for (var segment : segments.entrySet()) {
          Files.write(workingDirectory.resolve(segment.getKey()), segment.getValue());
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      stopProcess(sessionId, variantLabel);
      return process;
    }
  }
}
