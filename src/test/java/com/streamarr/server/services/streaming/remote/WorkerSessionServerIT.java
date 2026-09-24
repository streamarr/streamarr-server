package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.google.protobuf.ByteString;
import com.streamarr.server.domain.streaming.AudioDecision;
import com.streamarr.server.domain.streaming.SubtitleDecision;
import com.streamarr.server.domain.streaming.SubtitleMode;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeHandle;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.ProbeExecutionException;
import com.streamarr.server.fakes.BlockingSegmentStore;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamSessionFixture;
import com.streamarr.server.services.streaming.ExecutionTargetId;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.SegmentStore;
import com.streamarr.server.services.streaming.local.LocalSegmentStore;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStopped;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.Builder;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("IntegrationTest")
@DisplayName("Worker Session Server Integration Tests")
class WorkerSessionServerIT {

  private static final UUID AUTHENTICATED_WORKER_ID =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @ParameterizedTest
  @EnumSource(SessionEnd.class)
  @DisplayName("Should fail a pending probe when its session ends during segment publication")
  void shouldFailPendingProbeWhenItsSessionEndsDuringSegmentPublication(SessionEnd ending)
      throws Exception {
    var segmentStore = new PausedPublicationStore();
    var configuration =
        serverConfigurationBuilder()
            .probeTimeout(
                ending == SessionEnd.TIMED_OUT ? Duration.ofSeconds(2) : Duration.ofMinutes(1))
            .probeCancellationTimeout(Duration.ofMillis(100))
            .build();
    try (var server =
        new WorkerSessionServer(configuration, segmentStore, new SimpleMeterRegistry())) {
      server.start();
      var channel = workerChannel(server.port());
      var identity = workerIdentity(UUID.randomUUID());
      try {
        var worker = connectProbeWorker(channel, identity);
        var session = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().hasStartVariant()).isTrue();
        var request =
            ProbeRequest.newBuilder()
                .setProbeAttemptId(toProto(UUID.randomUUID()))
                .setProbeVersion(1)
                .setSource(job.getSource())
                .build();
        var pending = dispatched(server.dispatchProbe(request));
        assertThat(worker.nextResponse().getStartProbe().getRequest()).isEqualTo(request);
        var bytes = ByteString.copyFromUtf8("segment").toByteArray();
        var metadata =
            segmentMetadata(session, identity, job).setContentLengthBytes(bytes.length).build();
        var upload = upload(channel, metadata, bytes);
        try {
          assertThat(segmentStore.entered.await(5, TimeUnit.SECONDS)).isTrue();
          switch (ending) {
            case DISCONNECTED -> worker.close();
            case REPLACED -> {
              var replacement = connect(channel, workerIdentity(UUID.randomUUID()));
              assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();
            }
            case TIMED_OUT ->
                await()
                    .atMost(5, TimeUnit.SECONDS)
                    .untilAsserted(
                        () -> assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isFalse());
          }

          assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
              .as("ended-session probe must fail while segment publication remains blocked")
              .isInstanceOf(ExecutionException.class)
              .hasCauseInstanceOf(
                  ending == SessionEnd.TIMED_OUT
                      ? TimeoutException.class
                      : ProbeExecutionException.class);
        } finally {
          segmentStore.release.countDown();
        }

        upload.get(5, TimeUnit.SECONDS);
      } finally {
        segmentStore.release.countDown();
        shutdown(channel);
      }
    }
  }

  private TestWorkerConnection connectProbeWorker(ManagedChannel channel, WorkerIdentity identity) {
    var responses = new LinkedBlockingQueue<EstablishWorkerSessionResponse>();
    var closed = new CompletableFuture<Void>();
    var requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(new QueuedResponseObserver(responses, closed));
    var registration = registration(identity).toBuilder();
    registration.getRegistrationBuilder().setAvailableSlots(2);
    registration.getRegistrationBuilder().getCapabilitiesBuilder().addProbeVersions(1);
    requests.onNext(registration.build());
    return new TestWorkerConnection(requests, responses, closed);
  }

  @Test
  @DisplayName(
      "Should dispatch a replacement probe when the superseded segment publication is blocked")
  void shouldDispatchReplacementProbeWhenSupersededSegmentPublicationIsBlocked() throws Exception {
    var segmentStore = new PausedPublicationStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());
      var identity = workerIdentity(UUID.randomUUID());
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var worker = connectProbeWorker(channel, identity);
        var session = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().hasStartVariant()).isTrue();
        var request =
            ProbeRequest.newBuilder()
                .setProbeAttemptId(toProto(UUID.randomUUID()))
                .setProbeVersion(1)
                .setSource(job.getSource())
                .build();
        var superseded = dispatched(server.dispatchProbe(request));
        assertThat(worker.nextResponse().hasStartProbe()).isTrue();
        var bytes = ByteString.copyFromUtf8("segment").toByteArray();
        var metadata =
            segmentMetadata(session, identity, job).setContentLengthBytes(bytes.length).build();
        var upload = upload(channel, metadata, bytes);
        try {
          assertThat(segmentStore.entered.await(5, TimeUnit.SECONDS)).isTrue();
          var replacement = connectProbeWorker(channel, workerIdentity(UUID.randomUUID()));
          assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();
          assertThatThrownBy(() -> superseded.get(5, TimeUnit.SECONDS))
              .isInstanceOf(ExecutionException.class)
              .hasCauseInstanceOf(ProbeExecutionException.class);

          var dispatched = executor.submit(() -> server.dispatchProbe(request));
          var pending = dispatched(dispatched.get(5, TimeUnit.SECONDS));

          assertThat(replacement.nextResponse().getStartProbe().getRequest())
              .as("The replacement session must receive the same attempt")
              .isEqualTo(request);
          assertThat(pending).as("The replacement probe must wait for its worker").isNotDone();
          assertThat(upload)
              .as("The superseded segment publication must still be held")
              .isNotDone();
        } finally {
          segmentStore.release.countDown();
        }

        upload.get(5, TimeUnit.SECONDS);
      } finally {
        segmentStore.release.countDown();
        shutdown(channel);
      }
    }
  }

  private enum SessionEnd {
    DISCONNECTED,
    REPLACED,
    TIMED_OUT
  }

  private static final class PausedPublicationStore extends FakeSegmentStore {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] bytes) {
      var prepared = super.prepareSegment(sessionId, segmentName, bytes);
      return new PreparedSegment() {
        @Override
        public SegmentPublication publish() {
          entered.countDown();
          try {
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
          }

          return prepared.publish();
        }

        @Override
        public void close() {
          prepared.close();
        }
      };
    }
  }

  @Test
  @DisplayName("Should deliver a typed probe result when a capable worker replies over mutual TLS")
  void shouldDeliverTypedProbeResultWhenCapableWorkerRepliesOverMutualTls() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());
      try {
        var responses = new LinkedBlockingQueue<EstablishWorkerSessionResponse>();
        var closed = new CompletableFuture<Void>();
        var session =
            TranscodeWorkerServiceGrpc.newStub(channel)
                .establishWorkerSession(new QueuedResponseObserver(responses, closed));
        var registration = registration(AUTHENTICATED_WORKER_ID).toBuilder();
        registration.getRegistrationBuilder().getCapabilitiesBuilder().addProbeVersions(2);
        session.onNext(registration.build());
        assertThat(responses.poll(5, TimeUnit.SECONDS))
            .isNotNull()
            .satisfies(response -> assertThat(response.hasSessionAccepted()).isTrue());
        var request =
            ProbeRequest.newBuilder()
                .setProbeAttemptId(toProto(UUID.randomUUID()))
                .setProbeVersion(2)
                .setSource(variantJob().getSource())
                .build();

        var pending = dispatched(server.dispatchProbe(request));

        var command = responses.poll(5, TimeUnit.SECONDS);
        assertThat(command).isNotNull();
        assertThat(command.getStartProbe().getRequest()).isEqualTo(request);
        assertThat(command.getStartProbe().getTarget())
            .isEqualTo(registration.getRegistration().getWorker());
        var result =
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .setProbeVersion(2)
                .setFailure(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM)
                .build();
        session.onNext(EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build());

        assertThat(pending.get(5, TimeUnit.SECONDS)).isEqualTo(result);
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should register a worker whose reported identity matches its claimed identity when handling a worker session")
  void shouldRegisterWorkerWhoseReportedIdentityMatchesItsClaimedIdentityWhenHandlingWorkerSession()
      throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var response = register(channel, AUTHENTICATED_WORKER_ID).get(5, TimeUnit.SECONDS);

        assertThat(response.hasSessionAccepted()).isTrue();
        assertThat(response.getSessionAccepted().hasWorkerSessionId()).isTrue();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should enforce the worker session server lifecycle when server state changes")
  void shouldEnforceLifecycleWhenServerStateChanges() throws Exception {
    try (var server = server()) {
      assertThatThrownBy(() -> server.hasConnectedWorker(SOURCE_NAMESPACE_ID))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Worker session server is not started");
      assertThatThrownBy(server::port)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Worker session server is not started");
      server.close();

      server.start();

      assertThat(server.port()).isPositive();
      assertThat(server.hasConnectedWorker(SOURCE_NAMESPACE_ID)).isFalse();
      assertThatThrownBy(server::start)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Worker session server is already started");
      server.close();
      assertThatThrownBy(server::port)
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Worker session server is not started");
    }
  }

  @Test
  @DisplayName("Should reject configuration when worker session server settings are invalid")
  void shouldRejectConfigurationWhenWorkerSessionServerSettingsAreInvalid() {
    var negativePort = serverConfigurationBuilder().port(-1);
    var excessivePort = serverConfigurationBuilder().port(65_536);
    var blankAddress = serverConfigurationBuilder().address(" ");
    var nullAddress = serverConfigurationBuilder();

    assertThatThrownBy(negativePort::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Worker session port must be between 0 and 65535");
    assertThatThrownBy(excessivePort::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Worker session port must be between 0 and 65535");
    assertThatThrownBy(() -> nullAddress.address(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(blankAddress::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Worker session address is required");
  }

  @Test
  @DisplayName(
      "Should reject a reported worker identity that differs from its claimed identity when handling a worker session")
  void
      shouldRejectReportedWorkerIdentityThatDiffersFromItsClaimedIdentityWhenHandlingWorkerSession()
          throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var response = register(channel, UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));

        assertUploadRejected(response, Status.Code.PERMISSION_DENIED);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject a worker that omits its identity header when handling a worker session")
  void shouldRejectWorkerThatOmitsItsIdentityHeaderWhenHandlingWorkerSession() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = unauthenticatedChannel(server.port());

      try {
        var response = register(channel, AUTHENTICATED_WORKER_ID);

        assertUploadRejected(response, Status.Code.UNAUTHENTICATED);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject a worker session that does not begin with registration when handling a worker session")
  void shouldRejectWorkerSessionThatDoesNotBeginWithRegistrationWhenHandlingWorkerSession()
      throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var response =
            send(
                channel,
                EstablishWorkerSessionRequest.newBuilder()
                    .setJobAttemptCompleted(
                        JobAttemptCompleted.newBuilder()
                            .setJobAttemptId(toProto(UUID.randomUUID())))
                    .build());

        assertUploadRejected(response, Status.Code.INVALID_ARGUMENT);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should dispatch a variant job to a registered worker when handling a worker session")
  void shouldDispatchVariantJobToRegisteredWorkerWhenHandlingWorkerSession() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var job = variantJob();

        assertThat(server.dispatch(job)).isTrue();

        var command = worker.nextResponse().getStartVariant();
        assertThat(command.getTarget().getWorkerId()).isEqualTo(toProto(AUTHENTICATED_WORKER_ID));
        assertThat(command.getJob()).isEqualTo(job);
      } finally {
        shutdown(channel);
      }
    }
  }

  @ParameterizedTest
  @CsvSource({
    "BURN_IN,SUBTITLE_MODE_BURN_IN",
    "SIDECAR,SUBTITLE_MODE_SIDECAR",
    "HLS,SUBTITLE_MODE_HLS",
    "EMBED,SUBTITLE_MODE_EMBED"
  })
  @DisplayName("Should preserve subtitle selection when dispatching a remote transcode")
  void shouldPreserveSubtitleSelectionWhenDispatchingRemoteTranscode(
      SubtitleMode mode, String wireMode) throws Exception {
    var decision =
        fullTranscodeH264Decision()
            .subtitleDecision(
                subtitleSelection().mode(mode).codec("srt").streamIndex(2).language("eng").build())
            .build();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .transcodeDecision(decision)
            .build();

    var dispatched = dispatchThroughRemoteExecutor(request);

    var job = dispatched.job();
    assertThat(fromProto(job.getJobAttemptId())).isEqualTo(dispatched.handle().attemptId());
    var subtitle = job.getDecision().getSubtitle();
    assertThat(subtitle.getMode().name()).isEqualTo(wireMode);
    assertThat(subtitle.getCodec()).isEqualTo("srt");
    assertThat(subtitle.hasStreamIndex()).isTrue();
    assertThat(subtitle.getStreamIndex()).isEqualTo(2);
    assertThat(subtitle.getLanguage()).isEqualTo("eng");
  }

  @Test
  @DisplayName(
      "Should advertise the variant's media segment count to the worker when dispatching a remote transcode")
  void shouldAdvertiseVariantMediaSegmentCountToWorkerWhenDispatchingRemoteTranscode()
      throws Exception {
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .transcodeDecision(fullTranscodeH264Decision().build())
            .targetSegmentDuration(6)
            .startSequenceNumber(2)
            .mediaSegmentCount(1200)
            .build();

    var execution = dispatchThroughRemoteExecutor(request).job().getExecution();

    assertThat(execution.getStartSequenceNumber()).isEqualTo(2);
    assertThat(execution.getMediaSegmentCount()).isEqualTo(1200);
  }

  @Builder(builderMethodName = "subtitleSelection")
  private static SubtitleDecision subtitleDecision(
      SubtitleMode mode, String codec, int streamIndex, String language) {
    return new SubtitleDecision(
        mode, Optional.of(codec), OptionalInt.of(streamIndex), Optional.of(language));
  }

  private static TranscodeDecision.TranscodeDecisionBuilder fullTranscodeH264Decision() {
    return TranscodeDecision.builder()
        .transcodeMode(TranscodeMode.FULL_TRANSCODE)
        .videoCodecFamily("h264")
        .audioDecision(AudioDecision.stereoAac())
        .subtitleDecision(SubtitleDecision.exclude());
  }

  private DispatchedJob dispatchThroughRemoteExecutor(TranscodeRequest request) throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());
      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, Path.of("/media"));

        var handle = executor.start(request);

        return new DispatchedJob(handle, worker.nextResponse().getStartVariant().getJob());
      } finally {
        shutdown(channel);
      }
    }
  }

  private record DispatchedJob(TranscodeHandle handle, VariantJob job) {}

  @Test
  @DisplayName("Should stop dispatching when the worker connection closes")
  void shouldStopDispatchingWhenWorkerConnectionCloses() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var worker = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();

        worker.close();
        worker.awaitClosed();

        assertThat(server.dispatch(variantJob())).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should fence a replaced worker connection without removing its replacement when handling a worker session")
  void shouldFenceReplacedWorkerConnectionWithoutRemovingItsReplacementWhenHandlingWorkerSession()
      throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var first = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(first.nextResponse().hasSessionAccepted()).isTrue();
        var replacement = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();

        assertUploadRejected(first::awaitClosed, Status.Code.ABORTED);
        var job = variantJob();

        assertThat(server.dispatch(job)).isTrue();
        assertThat(replacement.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        replacement.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should decline dispatch when no worker is connected")
  void shouldDeclineDispatchWhenNoWorkerIsConnected() throws Exception {
    try (var server = server()) {
      server.start();

      assertThat(server.dispatch(variantJob())).isFalse();
    }
  }

  @Test
  @DisplayName("Should decline dispatch when the worker cannot access the source namespace")
  void shouldDeclineDispatchWhenWorkerCannotAccessSourceNamespace() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker =
          connect(
              channel,
              workerIdentity(AUTHENTICATED_WORKER_ID, UUID.randomUUID()),
              UUID.randomUUID())) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, Path.of("/media"));
        assertThat(executor.isHealthy()).isFalse();
        assertThat(executor.availableSlots()).isZero();

        assertThat(server.dispatch(variantJob())).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should bound active variant ownership by advertised worker capacity when handling a worker session")
  void shouldBoundActiveVariantOwnershipByAdvertisedWorkerCapacityWhenHandlingWorkerSession()
      throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
        var first = variantJob();
        var second = variantJob();
        assertThat(server.dispatch(first)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(first);
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isZero();

        assertThat(server.dispatch(second)).isFalse();

        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptCompleted(
                    JobAttemptCompleted.newBuilder().setJobAttemptId(first.getJobAttemptId()))
                .build());
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> server.availableSlots(SOURCE_NAMESPACE_ID) == 1);
        assertThat(server.dispatch(second)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(second);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject worker operations outside connection-owned variant state when handling a worker session")
  void shouldRejectWorkerOperationsOutsideConnectionOwnedVariantStateWhenHandlingWorkerSession()
      throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        assertThat(server.stopVariant(UUID.randomUUID(), "720p")).isFalse();
        assertThat(server.dispatch(variantJob().toBuilder().clearSource().build())).isFalse();
        assertThat(server.isRunning(UUID.randomUUID(), "720p")).isFalse();

        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        assertThat(server.isRunning(fromProto(job.getStreamSessionId()), "missing")).isFalse();
        var metadata =
            segmentMetadata(workerSession, identity, job).setContentLengthBytes(1).build();
        var unownedMetadata =
            List.of(
                metadata.toBuilder().setWorkerSessionId(toProto(UUID.randomUUID())).build(),
                metadata.toBuilder().setWorker(workerIdentity(UUID.randomUUID())).build(),
                metadata.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build(),
                metadata.toBuilder().setStreamSessionId(toProto(UUID.randomUUID())).build(),
                metadata.toBuilder().setJobId(toProto(UUID.randomUUID())).build(),
                metadata.toBuilder().setVariantLabel("other").build());

        unownedMetadata.forEach(
            unowned ->
                assertUploadRejected(
                    upload(channel, unowned, new byte[] {1}), Status.Code.PERMISSION_DENIED));
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject a segment uploaded by a replaced worker connection when handling a worker session")
  void shouldRejectSegmentUploadedByReplacedWorkerConnectionWhenHandlingWorkerSession()
      throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var firstIdentity = workerIdentity(UUID.randomUUID());
        var first = connect(channel, firstIdentity);
        var firstSession = first.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(first.nextResponse().getStartVariant().getJob()).isEqualTo(job);

        var replacement = connect(channel, workerIdentity(UUID.randomUUID()));
        assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();
        assertUploadRejected(first::awaitClosed, Status.Code.ABORTED);
        var segmentData = "stale segment".getBytes();
        var metadata =
            segmentMetadata(firstSession, firstIdentity, job)
                .setContentLengthBytes(segmentData.length)
                .build();

        assertUploadRejected(upload(channel, metadata, segmentData), Status.Code.PERMISSION_DENIED);
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();
        replacement.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should end the replacement attempt and keep the stored segments when its initialization segment differs")
  void shouldEndReplacementAttemptAndKeepStoredSegmentsWhenItsInitializationSegmentDiffers(
      @TempDir Path segments) throws Exception {
    var segmentStore = new LocalSegmentStore(segments);
    var meterRegistry = new SimpleMeterRegistry();
    var storedInitialization = "ftyp moov from encoder A".getBytes();
    var firstMediaSegment = "moof mdat of segment 0".getBytes();
    var differingInitialization = "ftyp moov from encoder B".getBytes();
    try (var server = server(segmentStore, meterRegistry)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var initial = variantJob();
        var streamSessionId = fromProto(initial.getStreamSessionId());
        assertThat(server.dispatch(initial)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(initial);
        var initialUpload = segmentMetadata(workerSession, identity, initial);
        upload(
                channel,
                namedSegment(initialUpload, "init.mp4", storedInitialization),
                storedInitialization)
            .get(5, TimeUnit.SECONDS);
        upload(
                channel,
                namedSegment(initialUpload, "segment0.m4s", firstMediaSegment),
                firstMediaSegment)
            .get(5, TimeUnit.SECONDS);
        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptFailed(
                    JobAttemptFailed.newBuilder()
                        .setJobAttemptId(initial.getJobAttemptId())
                        .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
                .build());
        await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId, "720p"));
        var replacement = initial.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build();
        assertThat(server.dispatch(replacement)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(replacement);

        var refused =
            upload(
                channel,
                namedSegment(
                    segmentMetadata(workerSession, identity, replacement),
                    "init.mp4",
                    differingInitialization),
                differingInitialization);

        assertUploadRejected(refused, Status.Code.FAILED_PRECONDITION);
        assertThat(worker.nextResponse().getStopVariant().getJobAttemptId())
            .isEqualTo(replacement.getJobAttemptId());
        assertThat(server.isRunning(streamSessionId, "720p")).isFalse();
        assertThat(server.availableSlots(SOURCE_NAMESPACE_ID)).isEqualTo(1);
        assertThat(InitializationSegmentMismatchMetric.count(meterRegistry)).isEqualTo(1);
        var laterMediaSegment = "moof mdat of segment 1".getBytes();
        assertUploadRejected(
            upload(
                channel,
                namedSegment(
                    segmentMetadata(workerSession, identity, replacement),
                    "segment1.m4s",
                    laterMediaSegment),
                laterMediaSegment),
            Status.Code.PERMISSION_DENIED);
        assertThat(segmentStore.readSegment(streamSessionId, "720p/init.mp4"))
            .isEqualTo(storedInitialization);
        assertThat(segmentStore.readSegment(streamSessionId, "720p/segment0.m4s"))
            .isEqualTo(firstMediaSegment);
        assertThat(segmentStore.segmentExists(streamSessionId, "720p/segment1.m4s")).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should not publish an incomplete segment upload when handling a worker session")
  void shouldNotPublishIncompleteSegmentUploadWhenHandlingWorkerSession() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var identity = workerIdentity(UUID.randomUUID());
        var worker = connect(channel, identity);
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var segmentData = "partial segment".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(segmentData.length + 1L)
                .build();

        assertUploadRejected(upload(channel, metadata, segmentData), Status.Code.INVALID_ARGUMENT);
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();
        worker.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject malformed segment uploads without publishing bytes when handling a worker session")
  void shouldRejectMalformedSegmentUploadsWithoutPublishingBytesWhenHandlingWorkerSession()
      throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var metadata =
            segmentMetadata(workerSession, identity, job).setContentLengthBytes(1).build();

        assertUploadRejected(uploadRequests(channel, List.of()), Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder()
                        .setData(ByteString.copyFromUtf8("x"))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(channel, List.of(UploadSegmentRequest.getDefaultInstance())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder()
                        .setMetadata(
                            metadata.toBuilder().setContentLengthBytes(16L * 1024 * 1024 + 1))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder()
                        .setMetadata(
                            metadata.toBuilder()
                                .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder().setMetadata(metadata).build(),
                    UploadSegmentRequest.newBuilder().setMetadata(metadata).build())),
            Status.Code.INVALID_ARGUMENT);
        assertUploadRejected(
            uploadRequests(
                channel,
                List.of(
                    UploadSegmentRequest.newBuilder().setMetadata(metadata).build(),
                    UploadSegmentRequest.newBuilder()
                        .setData(ByteString.copyFromUtf8("too long"))
                        .build())),
            Status.Code.INVALID_ARGUMENT);
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject an oversized segment upload frame at the transport boundary when handling a worker session")
  void shouldRejectOversizedSegmentUploadFrameAtTransportBoundaryWhenHandlingWorkerSession()
      throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var segmentData = new byte[128 * 1024];
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(segmentData.length)
                .build();

        assertUploadRejected(
            upload(channel, metadata, segmentData), Status.Code.RESOURCE_EXHAUSTED);
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();

        var validSegment = "complete segment".getBytes();
        var accepted =
            upload(
                    channel,
                    metadata.toBuilder().setContentLengthBytes(validSegment.length).build(),
                    validSegment)
                .get(5, TimeUnit.SECONDS);
        assertThat(accepted.getAcceptedLengthBytes()).isEqualTo(validSegment.length);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject unsafe segment metadata without publishing bytes when handling a worker session")
  void shouldRejectUnsafeSegmentMetadataWithoutPublishingBytesWhenHandlingWorkerSession()
      throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var metadata =
            segmentMetadata(workerSession, identity, job).setContentLengthBytes(1).build();
        var invalidMetadata =
            List.of(
                metadata.toBuilder().setContentLengthBytes(0).build(),
                metadata.toBuilder().setContentLengthBytes(-1).build(),
                metadata.toBuilder().setContentLengthBytes(16L * 1024 * 1024 + 1).build(),
                metadata.toBuilder().setSegmentName(" ").build(),
                metadata.toBuilder().setSegmentName("../segment0.m4s").build(),
                metadata.toBuilder().setSegmentName("nested/segment0.m4s").build(),
                metadata.toBuilder().setSegmentName("nested\\segment0.m4s").build(),
                metadata.toBuilder()
                    .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_UNSPECIFIED)
                    .build(),
                metadata.toBuilder()
                    .setSegmentName("segment0.ts")
                    .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP2T)
                    .build(),
                metadata.toBuilder().setContentTypeValue(999).build());

        invalidMetadata.forEach(
            unsafe ->
                assertUploadRejectedAsInvalidMetadata(upload(channel, unsafe, new byte[] {1})));
        assertThat(
                server.stopVariant(
                    fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
            .isTrue();
        assertThat(worker.nextResponse().hasStopVariant()).isTrue();

        for (var unsafeName : List.of(" ", "..", "720/p", "720\\p")) {
          var unsafeJob =
              variantJob().toBuilder()
                  .setVariant(VariantSpec.newBuilder().setVariantLabel(unsafeName))
                  .build();
          assertThat(server.dispatch(unsafeJob)).isTrue();
          assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(unsafeJob);
          var unsafe =
              segmentMetadata(workerSession, identity, unsafeJob).setContentLengthBytes(1).build();

          assertUploadRejectedAsInvalidMetadata(upload(channel, unsafe, new byte[] {1}));
          assertThat(
                  server.stopVariant(
                      fromProto(unsafeJob.getStreamSessionId()),
                      unsafeJob.getVariant().getVariantLabel()))
              .isTrue();
          assertThat(worker.nextResponse().hasStopVariant()).isTrue();
        }
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();
        assertThat(
                segmentStore.segmentExists(fromProto(job.getStreamSessionId()), "720p/segment0.ts"))
            .isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should report storage failure without accepting a segment when handling a worker session")
  void shouldReportStorageFailureWithoutAcceptingSegmentWhenHandlingWorkerSession()
      throws Exception {
    try (var server = server(new FailingSegmentStore())) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var data = "segment".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(data.length)
                .build();

        assertUploadRejected(upload(channel, metadata, data), Status.Code.INTERNAL);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject an upload that loses connection ownership before publication when handling a worker session")
  void shouldRejectUploadThatLosesConnectionOwnershipBeforePublicationWhenHandlingWorkerSession()
      throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var identity = workerIdentity(UUID.randomUUID());
        var worker = connect(channel, identity);
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var segmentData = ByteString.copyFromUtf8("in flight");
        var upload = beginSegmentUpload(channel);
        upload
            .requests()
            .onNext(
                UploadSegmentRequest.newBuilder()
                    .setMetadata(
                        segmentMetadata(workerSession, identity, job)
                            .setContentLengthBytes(segmentData.size()))
                    .build());
        upload.requests().onNext(UploadSegmentRequest.newBuilder().setData(segmentData).build());

        var replacement = connect(channel, workerIdentity(UUID.randomUUID()));
        assertThat(replacement.nextResponse().hasSessionAccepted()).isTrue();
        assertUploadRejected(worker::awaitClosed, Status.Code.ABORTED);
        upload.requests().onCompleted();

        assertUploadRejected(upload.response(), Status.Code.PERMISSION_DENIED);
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();
        replacement.close();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should reject an upload that loses ownership during publication when handling a worker session")
  void shouldRejectUploadThatLosesOwnershipDuringPublicationWhenHandlingWorkerSession()
      throws Exception {
    var segmentStore = new BlockingSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());
      var identity = workerIdentity(UUID.randomUUID());

      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
        var data = "segment".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(data.length)
                .build();
        var upload = upload(channel, metadata, data);
        assertThat(segmentStore.awaitPreparation(Duration.ofSeconds(5))).isTrue();

        assertThat(
                server.stopVariant(
                    fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
            .isTrue();
        assertThat(worker.nextResponse().hasStopVariant()).isTrue();
        segmentStore.continuePreparation();

        assertUploadRejected(upload, Status.Code.PERMISSION_DENIED);
        assertThat(
                segmentStore.segmentExists(
                    fromProto(job.getStreamSessionId()), "720p/segment0.m4s"))
            .isFalse();
      } finally {
        segmentStore.continuePreparation();
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should expose live worker connections as execution targets for their namespace when handling a worker session")
  void
      shouldExposeLiveWorkerConnectionsAsExecutionTargetsForTheirNamespaceWhenHandlingWorkerSession()
          throws Exception {
    try (var server = server()) {
      server.start();
      assertThat(server.eligibleWorkers(SOURCE_NAMESPACE_ID)).isEmpty();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var expectedTarget =
            new ExecutionTargetId(fromProto(workerSession.getWorkerSessionId()).toString());

        assertThat(server.eligibleWorkers(SOURCE_NAMESPACE_ID)).containsExactly(expectedTarget);
        assertThat(server.eligibleWorkers(UUID.randomUUID())).isEmpty();

        var job = variantJob();
        assertThat(server.dispatchTo(new ExecutionTargetId(UUID.randomUUID().toString()), job))
            .isFalse();
        assertThat(server.dispatchTo(expectedTarget, job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should dispatch jobs carrying the handle's attempt identity end to end when handling a worker session")
  void shouldDispatchJobsCarryingTheHandlesAttemptIdentityEndToEndWhenHandlingWorkerSession()
      throws Exception {
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .targetSegmentDuration(6)
            .framerate(OptionalDouble.of(23.976))
            .transcodeDecision(StreamSessionFixture.remuxDecision())
            .width(1920)
            .height(1080)
            .bitrate(5_000_000)
            .variantLabel("720p")
            .build();

    var dispatched = dispatchThroughRemoteExecutor(request);

    // The attempt identity is minted once, upstream: the dispatched job, the returned handle,
    // and any later failure result all name the same attempt.
    var handle = dispatched.handle();
    assertThat(fromProto(dispatched.job().getJobAttemptId())).isEqualTo(handle.attemptId());
    assertThat(handle.attemptId()).isEqualTo(request.attemptId());
  }

  @Test
  @DisplayName(
      "Should reject stale uploads after a reported failure when handling a worker session")
  void shouldRejectStaleUploadsAfterReportedFailureWhenHandlingWorkerSession() throws Exception {
    var segmentStore = new FakeSegmentStore();
    try (var server = server(segmentStore)) {
      server.start();
      var channel = workerChannel(server.port());

      var identity = workerIdentity(UUID.randomUUID());
      try (var worker = connect(channel, identity)) {
        var workerSession = worker.nextResponse().getSessionAccepted();
        var job = variantJob();
        var streamSessionId = fromProto(job.getStreamSessionId());
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);

        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptFailed(
                    JobAttemptFailed.newBuilder()
                        .setJobAttemptId(job.getJobAttemptId())
                        .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
                .build());
        await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId, "720p"));

        // The failed attempt no longer authorizes uploads: its data plane is fenced too.
        var stale = "stale".getBytes();
        var metadata =
            segmentMetadata(workerSession, identity, job)
                .setContentLengthBytes(stale.length)
                .build();
        assertUploadRejected(upload(channel, metadata, stale), Status.Code.PERMISSION_DENIED);
        assertThat(segmentStore.segmentExists(streamSessionId, "720p/segment0.m4s")).isFalse();
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName(
      "Should release attempts on completion and tolerate planned-stop confirmations when handling a worker session")
  void
      shouldReleaseAttemptsOnCompletionAndToleratePlannedStopConfirmationsWhenHandlingWorkerSession()
          throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try (var worker = connect(channel, AUTHENTICATED_WORKER_ID)) {
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var completedJob = variantJob();
        var completedSession = fromProto(completedJob.getStreamSessionId());
        assertThat(server.dispatch(completedJob)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(completedJob);
        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptCompleted(
                    JobAttemptCompleted.newBuilder()
                        .setJobAttemptId(completedJob.getJobAttemptId()))
                .build());
        await()
            .atMost(5, TimeUnit.SECONDS)
            .until(() -> !server.isRunning(completedSession, "720p"));

        var stoppedJob = variantJob();
        var stoppedSession = fromProto(stoppedJob.getStreamSessionId());
        assertThat(server.dispatch(stoppedJob)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(stoppedJob);
        assertThat(server.stopVariant(stoppedSession, "720p")).isTrue();
        assertThat(worker.nextResponse().hasStopVariant()).isTrue();
        worker.send(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptStopped(
                    JobAttemptStopped.newBuilder().setJobAttemptId(stoppedJob.getJobAttemptId()))
                .build());
        // The server-side stop already released the attempt; the worker's confirmation finds
        // nothing to release and must be tolerated without error — the variant stays stopped
        // throughout the window in which the late confirmation lands.
        await()
            .during(Duration.ofMillis(200))
            .atMost(Duration.ofSeconds(2))
            .until(() -> !server.isRunning(stoppedSession, "720p"));
      } finally {
        shutdown(channel);
      }
    }
  }

  @Test
  @DisplayName("Should release jobs abandoned by a closing worker when handling a worker session")
  void shouldReleaseJobsAbandonedByClosingWorkerWhenHandlingWorkerSession() throws Exception {
    try (var server = server()) {
      server.start();
      var channel = workerChannel(server.port());

      try {
        var worker = connect(channel, AUTHENTICATED_WORKER_ID);
        assertThat(worker.nextResponse().hasSessionAccepted()).isTrue();
        var job = variantJob();
        var streamSessionId = fromProto(job.getStreamSessionId());
        assertThat(server.dispatch(job)).isTrue();
        assertThat(worker.nextResponse().getStartVariant().getJob()).isEqualTo(job);

        worker.close();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !server.isRunning(streamSessionId, "720p"));
      } finally {
        shutdown(channel);
      }
    }
  }

  private WorkerSessionServer server() {
    return server(new FakeSegmentStore());
  }

  private WorkerSessionServer server(SegmentStore segmentStore) {
    return server(segmentStore, new SimpleMeterRegistry());
  }

  private WorkerSessionServer server(SegmentStore segmentStore, MeterRegistry meterRegistry) {
    return new WorkerSessionServer(
        serverConfigurationBuilder().build(), segmentStore, meterRegistry);
  }

  private ManagedChannel workerChannel(int port) {
    return plaintextChannelBuilder(port, AUTHENTICATED_WORKER_ID).build();
  }

  private ManagedChannel unauthenticatedChannel(int port) {
    return NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
  }

  private CompletableFuture<EstablishWorkerSessionResponse> register(
      ManagedChannel channel, UUID reportedWorkerId) {
    return send(channel, registration(reportedWorkerId));
  }

  private CompletableFuture<EstablishWorkerSessionResponse> send(
      ManagedChannel channel, EstablishWorkerSessionRequest request) {
    var response = new CompletableFuture<EstablishWorkerSessionResponse>();
    var requestObserver =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(new FirstResponseObserver(response));
    requestObserver.onNext(request);
    return response;
  }

  private TestWorkerConnection connect(ManagedChannel channel, UUID workerId) {
    return connect(channel, workerIdentity(workerId, UUID.randomUUID()));
  }

  private TestWorkerConnection connect(ManagedChannel channel, WorkerIdentity worker) {
    return connect(channel, worker, SOURCE_NAMESPACE_ID);
  }

  private TestWorkerConnection connect(
      ManagedChannel channel, WorkerIdentity worker, UUID sourceNamespaceId) {
    var responses = new LinkedBlockingQueue<EstablishWorkerSessionResponse>();
    var closed = new CompletableFuture<Void>();
    var requestObserver =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .establishWorkerSession(new QueuedResponseObserver(responses, closed));
    requestObserver.onNext(registration(worker, sourceNamespaceId));
    return new TestWorkerConnection(requestObserver, responses, closed);
  }

  private VariantJob variantJob() {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                .setRelativeKey("movie.mkv"))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
        .build();
  }

  private EstablishWorkerSessionRequest registration(UUID workerId) {
    return registration(workerIdentity(workerId, UUID.randomUUID()));
  }

  private EstablishWorkerSessionRequest registration(WorkerIdentity worker) {
    return registration(worker, SOURCE_NAMESPACE_ID);
  }

  private EstablishWorkerSessionRequest registration(
      WorkerIdentity worker, UUID sourceNamespaceId) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setRegistration(
            WorkerRegistration.newBuilder()
                .setWorker(worker)
                .setCapabilities(
                    WorkerCapabilities.newBuilder()
                        .addSourceNamespaceIds(toProto(sourceNamespaceId)))
                .setAvailableSlots(1))
        .build();
  }

  private WorkerIdentity workerIdentity(UUID bootId) {
    return workerIdentity(AUTHENTICATED_WORKER_ID, bootId);
  }

  private WorkerIdentity workerIdentity(UUID workerId, UUID bootId) {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(workerId))
        .setBootId(toProto(bootId))
        .build();
  }

  private SegmentUploadMetadata.Builder segmentMetadata(
      WorkerSessionAccepted workerSession, WorkerIdentity worker, VariantJob job) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(workerSession.getWorkerSessionId())
        .setWorker(worker)
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setVariantLabel(job.getVariant().getVariantLabel())
        .setSegmentName("segment0.m4s")
        .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4);
  }

  private static SegmentUploadMetadata namedSegment(
      SegmentUploadMetadata.Builder metadata, String segmentName, byte[] data) {
    return metadata.setSegmentName(segmentName).setContentLengthBytes(data.length).build();
  }

  private CompletableFuture<UploadSegmentResponse> upload(
      ManagedChannel channel, SegmentUploadMetadata metadata, byte[] data) {
    return uploadRequests(
        channel,
        List.of(
            UploadSegmentRequest.newBuilder().setMetadata(metadata).build(),
            UploadSegmentRequest.newBuilder().setData(ByteString.copyFrom(data)).build()));
  }

  private CompletableFuture<UploadSegmentResponse> uploadRequests(
      ManagedChannel channel, List<UploadSegmentRequest> requests) {
    var upload = beginSegmentUpload(channel);
    requests.forEach(upload.requests()::onNext);
    upload.requests().onCompleted();
    return upload.response();
  }

  private SegmentUploadAttempt beginSegmentUpload(ManagedChannel channel) {
    var response = new CompletableFuture<UploadSegmentResponse>();
    var requests =
        TranscodeWorkerServiceGrpc.newStub(channel)
            .uploadSegment(new UploadResponseObserver(response));
    return new SegmentUploadAttempt(requests, response);
  }

  private void assertUploadRejected(CompletableFuture<?> response, Status.Code expectedStatus) {
    assertUploadRejected(() -> response.get(5, TimeUnit.SECONDS), expectedStatus);
  }

  private void assertUploadRejected(ThrowingCallable operation, Status.Code expectedStatus) {
    assertThatThrownBy(operation)
        .rootCause()
        .matches(throwable -> Status.fromThrowable(throwable).getCode() == expectedStatus);
  }

  private void assertUploadRejectedAsInvalidMetadata(
      CompletableFuture<UploadSegmentResponse> response) {
    assertThatThrownBy(() -> response.get(5, TimeUnit.SECONDS))
        .rootCause()
        .matches(
            throwable -> {
              var status = Status.fromThrowable(throwable);
              return status.getCode() == Status.Code.INVALID_ARGUMENT
                  && status.getDescription().equals("Segment metadata is invalid");
            });
  }

  private void shutdown(ManagedChannel channel) throws InterruptedException {
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  private record FirstResponseObserver(CompletableFuture<EstablishWorkerSessionResponse> response)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      response.complete(value);
    }

    @Override
    public void onError(Throwable throwable) {
      response.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      if (!response.isDone()) {
        response.completeExceptionally(new IllegalStateException("Worker session closed"));
      }
    }
  }

  private record QueuedResponseObserver(
      BlockingQueue<EstablishWorkerSessionResponse> responses, CompletableFuture<Void> closed)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      closed.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      closed.complete(null);
    }
  }

  private record UploadResponseObserver(CompletableFuture<UploadSegmentResponse> response)
      implements StreamObserver<UploadSegmentResponse> {

    @Override
    public void onNext(UploadSegmentResponse value) {
      response.complete(value);
    }

    @Override
    public void onError(Throwable throwable) {
      response.completeExceptionally(throwable);
    }

    @Override
    public void onCompleted() {
      if (!response.isDone()) {
        response.completeExceptionally(new IllegalStateException("Segment upload closed"));
      }
    }
  }

  private record SegmentUploadAttempt(
      StreamObserver<UploadSegmentRequest> requests,
      CompletableFuture<UploadSegmentResponse> response) {}

  private static final class FailingSegmentStore extends FakeSegmentStore {

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      throw new IllegalStateException("Storage unavailable");
    }
  }

  private record TestWorkerConnection(
      StreamObserver<EstablishWorkerSessionRequest> requests,
      BlockingQueue<EstablishWorkerSessionResponse> responses,
      CompletableFuture<Void> closed)
      implements AutoCloseable {

    private EstablishWorkerSessionResponse nextResponse() throws InterruptedException {
      var response = responses.poll(5, TimeUnit.SECONDS);
      assertThat(response).as("worker session response must arrive").isNotNull();
      return response;
    }

    private void awaitClosed() throws Exception {
      closed.get(5, TimeUnit.SECONDS);
    }

    private void send(EstablishWorkerSessionRequest request) {
      requests.onNext(request);
    }

    @Override
    public void close() {
      requests.onCompleted();
    }
  }
}
