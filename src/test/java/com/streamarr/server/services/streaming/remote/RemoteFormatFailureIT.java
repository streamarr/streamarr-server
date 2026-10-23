package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.v1.TranscodeMode.TRANSCODE_MODE_REMUX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.TranscodeDecision;
import com.streamarr.server.domain.streaming.TranscodeFailure;
import com.streamarr.server.domain.streaming.TranscodeMode;
import com.streamarr.server.domain.streaming.TranscodeRequest;
import com.streamarr.server.exceptions.TranscodeException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeRuntimeStreamSessionRegistry;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.fixtures.StreamingRigFixture;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ApplicationEventPublisher;

@Tag("IntegrationTest")
@DisplayName("Remote Format Failure Integration Tests")
@Timeout(30)
class RemoteFormatFailureIT {

  @Test
  @DisplayName(
      "Should stop copy recovery when its output mismatch arrives before handle installation")
  void shouldStopCopyRecoveryWhenItsOutputMismatchArrivesBeforeHandleInstallation()
      throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      var handle = rig.executor.start(rig.requestBuilder().build());
      var job = worker.nextJob();
      worker.fail(job);
      rig.awaitStopped();
      rig.session.setHandle(handle);

      assertThat(rig.deliver()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(worker.jobs).hasSize(1);
      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class))
                      .containsExactly(eventFor(job)));
    }
  }

  @Test
  @DisplayName("Should retain the typed failure when a segment waiter wakes")
  void shouldRetainTypedFailureWhenSegmentWaiterWakes() throws Exception {
    try (var rig = new FailureRig(new CapturingEventPublisher());
        var worker = new TestWorker(rig.server);
        var waiting =
            rig.server
                .heldSegmentRequests()
                .nextWake(rig.session.getSessionId(), StreamSession.defaultVariant())) {
      rig.executor.start(rig.requestBuilder().build());
      worker.fail(worker.nextJob());

      assertThat(waiting.await(Duration.ofSeconds(5))).isTrue();
      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
          .contains(TranscodeFailure.OUTPUT_MISMATCH);
    }
  }

  @Test
  @DisplayName("Should retain a terminal copy failure when the session is suspended")
  void shouldRetainTerminalCopyFailureWhenSessionIsSuspended() throws Exception {
    try (var rig = new FailureRig(new CapturingEventPublisher());
        var worker = new TestWorker(rig.server)) {
      rig.session.setHandle(rig.executor.start(rig.requestBuilder().build()));
      worker.fail(worker.nextJob());
      rig.awaitStopped();

      rig.streaming.lifecycle().suspend(rig.session.getSessionId());

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
          .contains(TranscodeFailure.OUTPUT_MISMATCH);
      assertThat(rig.deliver()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      var restartRequest = rig.requestBuilder().build();
      assertThatThrownBy(() -> rig.executor.start(restartRequest))
          .isInstanceOf(TranscodeException.class);
      assertThat(worker.jobs).hasSize(1);
    }
  }

  @Test
  @DisplayName("Should discard a terminal copy failure when the stream session is destroyed")
  void shouldDiscardTerminalCopyFailureWhenStreamSessionIsDestroyed() throws Exception {
    try (var rig = new FailureRig(new CapturingEventPublisher());
        var worker = new TestWorker(rig.server)) {
      rig.session.setHandle(rig.executor.start(rig.requestBuilder().build()));
      worker.fail(worker.nextJob());
      rig.awaitStopped();
      var service =
          HlsStreamingService.builder()
              .meterRegistry(new SimpleMeterRegistry())
              .runtimeRegistry(rig.registry)
              .producerLifecycle(rig.streaming.lifecycle())
              .deliveryCoordinator(rig.streaming.coordinator())
              .segmentStore(rig.segments)
              .build();

      service.destroySession(rig.session.getSessionId());

      assertThat(rig.registry.findById(rig.session.getSessionId())).isEmpty();
      assertThat(rig.executor.terminalFailure(rig.session.getSessionId())).isEmpty();
    }
  }

  @Test
  @DisplayName("Should publish outside worker locks when the listener stops another producer")
  void shouldPublishOutsideWorkerLocksWhenListenerStopsAnotherProducer() throws Exception {
    var handled = new CompletableFuture<Void>();
    var events = new CapturingEventPublisher();
    try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      var server = new CompletableFuture<WorkerSessionServer>();
      ApplicationEventPublisher publisher =
          event -> {
            events.publishEvent(event);
            var mismatch = (VideoCopyOutputMismatchEvent) event;
            try {
              var listenerServer = server.get(5, TimeUnit.SECONDS);
              assertThat(listenerServer.terminalFailure(mismatch.streamSessionId()))
                  .contains(TranscodeFailure.OUTPUT_MISMATCH);
              tasks
                  .submit(() -> listenerServer.destroyStreamSession(mismatch.streamSessionId()))
                  .get(5, TimeUnit.SECONDS);
              handled.complete(null);
            } catch (Exception failure) {
              handled.completeExceptionally(failure);
            }
          };
      try (var rig = new FailureRig(publisher);
          var worker = new TestWorker(rig.server)) {
        server.complete(rig.server);
        rig.executor.start(rig.requestBuilder().build());
        worker.fail(worker.nextJob());

        handled.get(5, TimeUnit.SECONDS);

        assertThat(rig.executor.terminalFailure(rig.session.getSessionId())).isEmpty();
        assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class)).hasSize(1);
      }
    }
  }

  @Test
  @DisplayName("Should ignore duplicate and unknown failures when an attempt already ended")
  void shouldIgnoreDuplicateAndUnknownFailuresWhenAttemptAlreadyEnded() throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().build());
      var job = worker.nextJob();
      worker.fail(job);
      worker.fail(job);
      worker.fail(job.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build());
      worker.awaitProcessed(rig.server);

      assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class))
          .containsExactly(eventFor(job));
      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
          .contains(TranscodeFailure.OUTPUT_MISMATCH);
    }
  }

  @Test
  @DisplayName("Should ignore a stale attempt failure when a replacement producer is running")
  void shouldIgnoreStaleAttemptFailureWhenReplacementProducerIsRunning() throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().build());
      var oldJob = worker.nextJob();
      rig.executor.stopVariant(rig.session.getSessionId(), StreamSession.defaultVariant());
      rig.executor.start(rig.requestBuilder().build());
      var newJob = worker.nextJob();

      worker.fail(oldJob);
      worker.awaitProcessed(rig.server);

      assertThat(rig.executor.isRunning(rig.session.getSessionId(), StreamSession.defaultVariant()))
          .isTrue();
      assertThat(rig.executor.terminalFailure(rig.session.getSessionId())).isEmpty();
      assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class)).isEmpty();
      worker.fail(newJob);
      rig.awaitStopped();
      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class))
                      .containsExactly(eventFor(newJob)));
    }
  }

  @Test
  @DisplayName("Should preserve failure fencing when its worker reconnects")
  void shouldPreserveFailureFencingWhenItsWorkerReconnects() throws Exception {
    try (var rig = new FailureRig(new CapturingEventPublisher());
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().build());
      worker.fail(worker.nextJob());
      rig.awaitStopped();
      worker.close();
      try (var replacement = new TestWorker(rig.server, worker.workerId)) {
        var restartRequest = rig.requestBuilder().build();
        assertThatThrownBy(() -> rig.executor.start(restartRequest))
            .isInstanceOf(TranscodeException.class);
        var target =
            rig.executor.executionTargets(rig.session.getTranscodeDecision()).iterator().next();
        var targetedRequest = rig.requestBuilder().build();
        assertThatThrownBy(() -> rig.executor.start(targetedRequest, target))
            .isInstanceOf(TranscodeException.class);
        assertThat(replacement.jobs).isEmpty();
        assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
            .contains(TranscodeFailure.OUTPUT_MISMATCH);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = JobAttemptFailure.class,
      names = {"JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED", "JOB_ATTEMPT_FAILURE_SOURCE_UNAVAILABLE"})
  @DisplayName("Should retain ordinary recovery when the worker reports an ordinary job failure")
  void shouldRetainOrdinaryRecoveryWhenWorkerReportsOrdinaryJobFailure(JobAttemptFailure failure)
      throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server);
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      rig.session.setHandle(rig.executor.start(rig.requestBuilder().build()));
      worker.fail(worker.nextJob(), failure);
      rig.awaitStopped();
      var delivery = tasks.submit(rig::deliver);
      var recovered = worker.nextJob();
      rig.segments.storeSegment(rig.session.getSessionId(), "init.mp4", new byte[] {1});
      rig.server.heldSegmentRequests().wakeSession(rig.session.getSessionId());

      assertThat(delivery.get(5, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
      assertThat(worker.jobs).hasSize(2);
      assertThat(recovered.getDecision().getMode()).isEqualTo(TRANSCODE_MODE_REMUX);
      assertThat(rig.executor.terminalFailure(rig.session.getSessionId())).isEmpty();
      assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class)).isEmpty();
    }
  }

  @ParameterizedTest
  @CsvSource({
    "REMUX,true",
    "AUDIO_TRANSCODE,true",
    "VIDEO_TRANSCODE,false",
    "FULL_TRANSCODE,false"
  })
  @DisplayName("Should treat output mismatch as terminal only when the decision copies video")
  void shouldTreatOutputMismatchAsTerminalOnlyWhenDecisionCopiesVideo(
      TranscodeMode mode, boolean terminal) throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      var original = rig.session.getTranscodeDecision();
      var decision =
          TranscodeDecision.builder()
              .transcodeMode(mode)
              .videoCodecFamily(original.videoCodecFamily())
              .audioDecision(original.audioDecision())
              .subtitleDecision(original.subtitleDecision())
              .videoSource(original.videoSource())
              .build();
      rig.executor.start(rig.requestBuilder().transcodeDecision(decision).build());
      var job = worker.nextJob();
      worker.fail(job);
      worker.awaitProcessed(rig.server);

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()).isPresent())
          .isEqualTo(terminal);
      assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class))
          .hasSize(terminal ? 1 : 0);
    }
  }

  @Test
  @DisplayName("Should discard retained output failures when the worker session server closes")
  void shouldDiscardRetainedOutputFailuresWhenWorkerSessionServerCloses() throws Exception {
    try (var rig = new FailureRig(new CapturingEventPublisher());
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().build());
      worker.fail(worker.nextJob());
      rig.awaitStopped();

      rig.server.close();
      rig.server.start();

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId())).isEmpty();
    }
  }

  @Test
  @DisplayName("Should ignore a delayed output failure when its stream session was destroyed")
  void shouldIgnoreDelayedOutputFailureWhenStreamSessionWasDestroyed() throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().build());
      var job = worker.nextJob();
      rig.executor.destroySession(rig.session.getSessionId());

      worker.fail(job);
      worker.awaitProcessed(rig.server);

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId())).isEmpty();
      assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class)).isEmpty();
    }
  }

  private static VideoCopyOutputMismatchEvent eventFor(VariantJob job) {
    return new VideoCopyOutputMismatchEvent(
        fromProto(job.getStreamSessionId()),
        fromProto(job.getJobAttemptId()),
        job.getVariant().getVariantLabel());
  }

  private static final class FailureRig implements AutoCloseable {
    private final FakeSegmentStore segments = new FakeSegmentStore();
    private final FakeRuntimeStreamSessionRegistry registry =
        new FakeRuntimeStreamSessionRegistry();
    private final StreamSession session = defaultSessionBuilder().build();
    private final WorkerSessionServer server;
    private final RemoteTranscodeExecutor executor;
    private final StreamingRigFixture.StreamingRig streaming;

    private FailureRig(ApplicationEventPublisher events) throws Exception {
      server =
          WorkerSessionServer.builder()
              .configuration(serverConfigurationBuilder().build())
              .meterRegistry(new SimpleMeterRegistry())
              .segmentStore(segments)
              .eventPublisher(events)
              .build();
      server.start();
      executor = new RemoteTranscodeExecutor(server, SOURCE_NAMESPACE_ID, Path.of("/media"));
      registry.save(session);
      streaming =
          StreamingRigFixture.streamingRigBuilder()
              .transcodeExecutor(executor)
              .segmentStore(segments)
              .runtimeRegistry(registry)
              .properties(StreamingProperties.builder().build())
              .heldRequests(server.heldSegmentRequests())
              .build();
    }

    private TranscodeRequest.TranscodeRequestBuilder requestBuilder() {
      return TranscodeRequest.builder()
          .sessionId(session.getSessionId())
          .sourcePath(session.getSourcePath())
          .transcodeDecision(session.getTranscodeDecision())
          .targetSegmentDuration(6)
          .mediaSegmentCount(1200)
          .encoderPreset(EncoderPreset.FASTER)
          .framerate(OptionalDouble.of(24))
          .width(1920)
          .height(1080)
          .bitrate(5_000_000);
    }

    private SegmentDelivery deliver() {
      return streaming
          .coordinator()
          .deliver(session.getSessionId(), StreamSession.defaultVariant(), "init.mp4");
    }

    private void awaitStopped() {
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(() -> !executor.isRunning(session.getSessionId(), StreamSession.defaultVariant()));
    }

    @Override
    public void close() {
      server.close();
    }
  }

  private static final class TestWorker implements AutoCloseable {
    private final UUID workerId;
    private final ManagedChannel channel;
    private final StreamObserver<EstablishWorkerSessionRequest> requests;
    private final List<VariantJob> jobs = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<VariantJob> received = new LinkedBlockingQueue<>();

    private TestWorker(WorkerSessionServer server) throws Exception {
      this(server, UUID.randomUUID());
    }

    private TestWorker(WorkerSessionServer server, UUID workerId) throws Exception {
      this.workerId = workerId;
      channel = plaintextChannelBuilder(server.port(), workerId).build();
      var accepted = new CompletableFuture<Void>();
      requests =
          TranscodeWorkerServiceGrpc.newStub(channel)
              .establishWorkerSession(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(EstablishWorkerSessionResponse response) {
                      if (response.hasSessionAccepted()) {
                        accepted.complete(null);
                        return;
                      }

                      if (response.hasStartVariant()) {
                        var job = response.getStartVariant().getJob();
                        jobs.add(job);
                        received.add(job);
                      }
                    }

                    @Override
                    public void onError(Throwable failure) {
                      accepted.completeExceptionally(failure);
                    }

                    @Override
                    public void onCompleted() {
                      // TestWorker.close owns channel cleanup; completion needs no additional
                      // action.
                    }
                  });
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(
                  WorkerRegistration.newBuilder()
                      .setWorker(
                          WorkerIdentity.newBuilder()
                              .setWorkerId(toProto(workerId))
                              .setBootId(toProto(UUID.randomUUID())))
                      .setCapabilities(
                          WorkerCapabilities.newBuilder()
                              .addProbeVersions(1)
                              .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                              .addVideoEncoders(
                                  softwareEncoder(
                                      VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")))
                      .setAvailableSlots(2)
                      .setBackgroundProbeCapacity(1)
                      .setKeyframePacketScanCapacity(1))
              .build());
      accepted.get(5, TimeUnit.SECONDS);
    }

    private VariantJob nextJob() throws InterruptedException {
      var job = received.poll(5, TimeUnit.SECONDS);
      assertThat(job).isNotNull();
      return job;
    }

    private void fail(VariantJob job) {
      fail(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH);
    }

    private void fail(VariantJob job, JobAttemptFailure failure) {
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setJobAttemptFailed(
                  JobAttemptFailed.newBuilder()
                      .setJobAttemptId(job.getJobAttemptId())
                      .setFailure(failure))
              .build());
    }

    private void awaitProcessed(WorkerSessionServer server) throws Exception {
      // The stream processes messages in order; a completed probe behind the failures is a barrier.
      var request =
          ProbeRequest.newBuilder()
              .setProbeAttemptId(toProto(UUID.randomUUID()))
              .setProbeVersion(1)
              .setSource(
                  MediaSourceRef.newBuilder()
                      .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                      .setRelativeKey("barrier.mkv"))
              .build();
      var probe = dispatched(server.dispatchProbe(request));
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setProbeResult(
                  ProbeAttemptResult.newBuilder()
                      .setProbeAttemptId(request.getProbeAttemptId())
                      .setProbeVersion(1))
              .build());
      probe.get(5, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
