package com.streamarr.transcode.worker;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.remuxEngine;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.tlsResource;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.streamarr.server.fakes.FakeFfmpegProcessManager;
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
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Control Plane Integration Tests")
class TranscodeWorkerControlPlaneIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should warn when the control plane sends an unknown command")
  void shouldWarnWhenControlPlaneSendsUnknownCommand() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var service = new ControllableWorkerService();
    var appender = attachWorkerAppender();

    try (var server = new TestServer(service);
        var worker = worker(mediaRoot)) {
      server.start();
      worker.start("localhost", server.port());

      service.send(EstablishWorkerSessionResponse.getDefaultInstance());

      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(appender.list)
                      .anySatisfy(
                          event -> {
                            assertThat(event.getLevel()).isEqualTo(Level.WARN);
                            assertThat(event.getFormattedMessage()).contains("COMMAND_NOT_SET");
                          }));
    } finally {
      detachWorkerAppender(appender);
    }
  }

  @Test
  @DisplayName("Should reject a start command addressed to another worker")
  void shouldRejectStartCommandAddressedToAnotherWorker() throws Exception {
    assertRejectsStartCommand(
        target -> target.toBuilder().setWorkerId(toProto(UUID.randomUUID())).build());
  }

  @Test
  @DisplayName("Should reject a start command addressed to an earlier worker boot")
  void shouldRejectStartCommandAddressedToEarlierWorkerBoot() throws Exception {
    assertRejectsStartCommand(
        target -> target.toBuilder().setBootId(toProto(UUID.randomUUID())).build());
  }

  private void assertRejectsStartCommand(UnaryOperator<WorkerIdentity> changeTarget)
      throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    Files.writeString(mediaRoot.resolve("movie.mkv"), "test media");
    var service = new ControllableWorkerService();
    var processManager = new FakeFfmpegProcessManager();
    var job = variantJob();

    try (var server = new TestServer(service);
        var worker = worker(mediaRoot, processManager)) {
      server.start();
      worker.start("localhost", server.port());

      service.sendStart(changeTarget.apply(service.registeredWorker()), job);

      var failure = service.awaitFailure();
      assertThat(failure.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(failure.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(processManager.getStarted()).isEmpty();
    }
  }

  private TranscodeWorker worker(Path mediaRoot) throws Exception {
    return worker(mediaRoot, new FakeFfmpegProcessManager());
  }

  private TranscodeWorker worker(Path mediaRoot, FakeFfmpegProcessManager processManager)
      throws Exception {
    var configuration =
        workerConfigurationBuilder()
            .availableSlots(1)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .segmentBasePath(tempDir.resolve("segments"))
            .build();
    return new TranscodeWorker(configuration, remuxEngine(processManager));
  }

  private static VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
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

  private static ListAppender<ILoggingEvent> attachWorkerAppender() {
    var logger = (Logger) LoggerFactory.getLogger(TranscodeWorker.class);
    logger.setLevel(Level.WARN);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    return appender;
  }

  private static void detachWorkerAppender(ListAppender<ILoggingEvent> appender) {
    var logger = (Logger) LoggerFactory.getLogger(TranscodeWorker.class);
    logger.detachAppender(appender);
    logger.setLevel(null);
  }

  private static final class ControllableWorkerService
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

    private final CountDownLatch registered = new CountDownLatch(1);
    private final AtomicReference<WorkerIdentity> worker = new AtomicReference<>();
    private final BlockingQueue<EstablishWorkerSessionRequest> events = new LinkedBlockingQueue<>();
    private StreamObserver<EstablishWorkerSessionResponse> responses;

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      responses = responseObserver;
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest request) {
          if (!request.hasRegistration()) {
            events.add(request);
            return;
          }
          worker.set(request.getRegistration().getWorker());
          responseObserver.onNext(
              EstablishWorkerSessionResponse.newBuilder()
                  .setSessionAccepted(
                      WorkerSessionAccepted.newBuilder()
                          .setWorkerSessionId(toProto(UUID.randomUUID())))
                  .build());
          registered.countDown();
        }

        @Override
        public void onError(Throwable throwable) {
          // The control-plane assertions observe queued events instead.
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
      throw new AssertionError("No upload is expected");
    }

    private void send(EstablishWorkerSessionResponse response) throws InterruptedException {
      assertThat(registered.await(5, TimeUnit.SECONDS)).isTrue();
      responses.onNext(response);
    }

    private WorkerIdentity registeredWorker() {
      return worker.get();
    }

    private void sendStart(WorkerIdentity target, VariantJob job) throws InterruptedException {
      send(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartVariant(StartVariantCommand.newBuilder().setTarget(target).setJob(job))
              .build());
    }

    private JobAttemptFailed awaitFailure() throws InterruptedException {
      var event = events.poll(5, TimeUnit.SECONDS);
      assertThat(event).isNotNull();
      assertThat(event.hasJobAttemptFailed()).isTrue();
      return event.getJobAttemptFailed();
    }
  }

  private static final class TestServer implements AutoCloseable {

    private final ControllableWorkerService service;
    private Server server;

    private TestServer(ControllableWorkerService service) {
      this.service = service;
    }

    private void start() throws Exception {
      var sslContext =
          GrpcSslContexts.forServer(
                  tlsResource("server-cert.pem").toFile(),
                  tlsResource("server-key.fixture").toFile())
              .trustManager(tlsResource("ca-cert.pem").toFile())
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
