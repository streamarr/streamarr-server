package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.fakes.FakeFfmpegProcessManager;
import com.streamarr.server.fakes.FakeSegmentProducingFfmpegProcessManager;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegCommandBuilder;
import com.streamarr.server.services.streaming.ffmpeg.FfmpegTranscodeEngine;
import com.streamarr.server.services.streaming.ffmpeg.TranscodeCapabilityService;
import com.streamarr.transcode.tls.PemTlsIdentity;
import com.streamarr.transcode.v1.AudioDecision;
import com.streamarr.transcode.v1.AudioMode;
import com.streamarr.transcode.v1.ContainerFormat;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.StartVariantCommand;
import com.streamarr.transcode.v1.SubtitleDecision;
import com.streamarr.transcode.v1.SubtitleMode;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeExecution;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Upload Protocol Integration Tests")
class TranscodeWorkerUploadProtocolIT {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should cancel the upload RPC when the acknowledgement times out")
  void shouldCancelUploadRpcWhenAcknowledgementTimesOut() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager =
        new FakeSegmentProducingFfmpegProcessManager("segment0.ts", "segment".getBytes());
    var service = ControllableUploadService.leavesUploadRpcOpen();

    try (var server = new TestServer(service);
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(variantJob());

      assertThat(service.uploadCompleted.await(5, TimeUnit.SECONDS)).isTrue();
      await().atMost(8, TimeUnit.SECONDS).until(() -> processManager.getStopped().size() == 1);
      assertThat(service.failureReported.await(1, TimeUnit.SECONDS)).isTrue();

      assertThat(service.uploadCancelled.await(1, TimeUnit.SECONDS))
          .as("abandoned upload must be cancelled once the acknowledgement times out")
          .isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should fail a variant when the server acknowledges fewer bytes than the worker uploaded")
  void shouldFailVariantWhenServerAcknowledgesFewerBytesThanWorkerUploaded() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager =
        new FakeSegmentProducingFfmpegProcessManager("segment0.ts", "segment".getBytes());
    var service = ControllableUploadService.acknowledgesFewerBytes();
    var job = variantJob();

    try (var server = new TestServer(service);
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(job);

      assertThat(service.failureReported.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(service.failure.get().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(service.failure.get().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(processManager.getStopped()).contains(fromProto(job.getStreamSessionId()));
    }
  }

  @Test
  @DisplayName(
      "Should fail a variant promptly when the upload stream closes before acknowledgement")
  void shouldFailVariantPromptlyWhenUploadStreamClosesBeforeAcknowledgement() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var processManager =
        new FakeSegmentProducingFfmpegProcessManager("segment0.ts", "segment".getBytes());
    var service = ControllableUploadService.closesWithoutResponse();
    var job = variantJob();

    try (var server = new TestServer(service);
        var worker = worker(processManager, mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(job);

      assertThat(service.uploadCompleted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(service.failureReported.await(2, TimeUnit.SECONDS))
          .as("a clean close must fail before the upload acknowledgement timeout")
          .isTrue();
      assertThat(service.failure.get().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(service.failure.get().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(processManager.getStopped()).contains(fromProto(job.getStreamSessionId()));
    }
  }

  @Test
  @DisplayName(
      "Should report startup failure when FFmpeg becomes incompatible after worker registration")
  void shouldReportStartupFailureWhenFfmpegBecomesIncompatibleAfterWorkerRegistration()
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var service = ControllableUploadService.leavesUploadRpcOpen();
    var job = variantJob();

    try (var server = new TestServer(service);
        var worker = worker(unavailableEngine(), mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());
      service.dispatch(job);

      assertThat(service.failureReported.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(service.failure.get().getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(service.failure.get().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
    }
  }

  private TranscodeWorker worker(
      FakeSegmentProducingFfmpegProcessManager processManager, Path mediaRoot)
      throws URISyntaxException {
    return worker(remuxEngine(processManager), mediaRoot);
  }

  private TranscodeWorker worker(FfmpegTranscodeEngine engine, Path mediaRoot)
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
            .segmentBasePath(tempDir.resolve("segments"))
            .build();
    return new TranscodeWorker(configuration, engine);
  }

  private FfmpegTranscodeEngine unavailableEngine() {
    var capabilities =
        new TranscodeCapabilityService(
            "ffmpeg",
            _ -> {
              throw new IOException("FFmpeg disappeared");
            });
    capabilities.detectCapabilities();
    return new FfmpegTranscodeEngine(
        new FfmpegCommandBuilder("ffmpeg"), new FakeFfmpegProcessManager(), capabilities);
  }

  private VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(uuid(UUID.randomUUID()))
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
                .setContainer(ContainerFormat.CONTAINER_FORMAT_MPEG_TS)
                .setAlignKeyframesToSegments(true))
        .setVariant(
            VariantSpec.newBuilder()
                .setVariantLabel("720p")
                .setWidth(1920)
                .setHeight(1080)
                .setBitrateBitsPerSecond(5_000_000))
        .setExecution(
            TranscodeExecution.newBuilder().setTargetSegmentDurationSeconds(6).setFramerate(23.976))
        .build();
  }

  private Uuid uuid(UUID value) {
    return Uuid.newBuilder()
        .setMostSignificantBits(value.getMostSignificantBits())
        .setLeastSignificantBits(value.getLeastSignificantBits())
        .build();
  }

  private Path resource(String name) throws URISyntaxException {
    var url = getClass().getResource("/tls/" + name);
    assertThat(url).as("TLS resource %s must exist", name).isNotNull();
    return Path.of(url.toURI());
  }

  private static final class ControllableUploadService
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

    private final UploadResponseBehavior uploadResponseBehavior;
    private final AtomicReference<StreamObserver<EstablishWorkerSessionResponse>> control =
        new AtomicReference<>();
    private final AtomicReference<WorkerIdentity> worker = new AtomicReference<>();
    private final AtomicReference<JobAttemptFailed> failure = new AtomicReference<>();
    private final CountDownLatch registered = new CountDownLatch(1);
    private final CountDownLatch uploadCompleted = new CountDownLatch(1);
    private final CountDownLatch uploadCancelled = new CountDownLatch(1);
    private final CountDownLatch failureReported = new CountDownLatch(1);

    private ControllableUploadService(UploadResponseBehavior uploadResponseBehavior) {
      this.uploadResponseBehavior = uploadResponseBehavior;
    }

    private static ControllableUploadService leavesUploadRpcOpen() {
      return new ControllableUploadService(UploadResponseBehavior.LEAVES_RPC_OPEN);
    }

    private static ControllableUploadService acknowledgesFewerBytes() {
      return new ControllableUploadService(UploadResponseBehavior.ACKNOWLEDGES_FEWER_BYTES);
    }

    private static ControllableUploadService closesWithoutResponse() {
      return new ControllableUploadService(UploadResponseBehavior.CLOSES_WITHOUT_RESPONSE);
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      control.set(responseObserver);
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest request) {
          if (request.hasRegistration()) {
            worker.set(request.getRegistration().getWorker());
            responseObserver.onNext(
                EstablishWorkerSessionResponse.newBuilder()
                    .setSessionAccepted(
                        WorkerSessionAccepted.newBuilder()
                            .setWorkerSessionId(uuidStatic(UUID.randomUUID())))
                    .build());
            registered.countDown();
          }
          if (request.hasJobAttemptFailed()) {
            failure.set(request.getJobAttemptFailed());
            failureReported.countDown();
          }
        }

        @Override
        public void onError(Throwable throwable) {
          // Cancellation is asserted through the upload stream.
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public StreamObserver<UploadSegmentRequest> uploadSegment(
        StreamObserver<UploadSegmentResponse> responseObserver) {
      ((ServerCallStreamObserver<UploadSegmentResponse>) responseObserver)
          .setOnCancelHandler(uploadCancelled::countDown);
      return new StreamObserver<>() {
        @Override
        public void onNext(UploadSegmentRequest request) {
          // Uploaded frames are irrelevant; only cancellation matters.
        }

        @Override
        public void onError(Throwable throwable) {
          uploadCancelled.countDown();
        }

        @Override
        public void onCompleted() {
          uploadCompleted.countDown();
          switch (uploadResponseBehavior) {
            case LEAVES_RPC_OPEN -> {
              // Keep the response open so the client must cancel the timed-out upload.
            }
            case ACKNOWLEDGES_FEWER_BYTES -> {
              responseObserver.onNext(
                  UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(0).build());
              responseObserver.onCompleted();
            }
            case CLOSES_WITHOUT_RESPONSE -> responseObserver.onCompleted();
          }
        }
      };
    }

    private void dispatch(VariantJob job) throws InterruptedException {
      assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
      control
          .get()
          .onNext(
              EstablishWorkerSessionResponse.newBuilder()
                  .setStartVariant(
                      StartVariantCommand.newBuilder().setTarget(worker.get()).setJob(job))
                  .build());
    }

    private static Uuid uuidStatic(UUID value) {
      return Uuid.newBuilder()
          .setMostSignificantBits(value.getMostSignificantBits())
          .setLeastSignificantBits(value.getLeastSignificantBits())
          .build();
    }

    private enum UploadResponseBehavior {
      LEAVES_RPC_OPEN,
      ACKNOWLEDGES_FEWER_BYTES,
      CLOSES_WITHOUT_RESPONSE
    }
  }

  private final class TestServer implements AutoCloseable {

    private final ControllableUploadService service;
    private Server server;

    private TestServer(ControllableUploadService service) {
      this.service = service;
    }

    private void start() throws Exception {
      var sslContext =
          GrpcSslContexts.forServer(
                  resource("server-cert.pem").toFile(), resource("server-key.fixture").toFile())
              .trustManager(resource("ca-cert.pem").toFile())
              .clientAuth(ClientAuth.REQUIRE)
              .build();
      server =
          NettyServerBuilder.forPort(0).sslContext(sslContext).addService(service).build().start();
    }

    private int port() {
      return server.getPort();
    }

    @Override
    public void close() throws InterruptedException {
      if (server == null) {
        return;
      }
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
