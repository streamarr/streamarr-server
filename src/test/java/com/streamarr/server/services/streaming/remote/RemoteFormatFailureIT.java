package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.PlaybackLanguagePreferencesFixture.noLanguagePreferences;
import static com.streamarr.server.fixtures.ProbeFixture.keyframesEvery;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.plaintextChannelBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.serverConfigurationBuilder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultProbeBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.defaultSessionBuilder;
import static com.streamarr.server.fixtures.StreamSessionFixture.fullTranscodeDecision;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.v1.TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE;
import static com.streamarr.transcode.v1.TranscodeMode.TRANSCODE_MODE_REMUX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.ExactSegmentStarts;
import com.streamarr.server.domain.streaming.Fraction;
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
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.streaming.HlsStreamingService;
import com.streamarr.server.services.streaming.SegmentDelivery;
import com.streamarr.server.services.streaming.events.DolbyVisionConversionFailedEvent;
import com.streamarr.server.services.streaming.events.TerminalTranscodeFailureEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
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
import java.util.Optional;
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

  private static final int PROBE_VERSION = 1;

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
              .languagePreferences(noLanguagePreferences())
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
      names = {
        "JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED",
        "JOB_ATTEMPT_FAILURE_SOURCE_UNAVAILABLE",
        "JOB_ATTEMPT_FAILURE_STARTUP_FAILED",
        "JOB_ATTEMPT_FAILURE_COPY_CAP_REACHED"
      })
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
  @DisplayName("Should treat output mismatch as terminal when the decision copies or encodes video")
  void shouldTreatOutputMismatchAsTerminalWhenDecisionCopiesOrEncodesVideo(
      TranscodeMode mode, boolean copiesVideo) throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().transcodeDecision(rig.decision(mode)).build());
      var job = worker.nextJob();
      worker.fail(job);
      worker.awaitProcessed(rig.server);

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
          .contains(TranscodeFailure.OUTPUT_MISMATCH);
      assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class))
          .hasSize(copiesVideo ? 1 : 0);
      assertThat(events.getEventsOfType(TerminalTranscodeFailureEvent.class))
          .isEqualTo(
              copiesVideo
                  ? List.of()
                  : List.of(terminalEventFor(job, TranscodeFailure.OUTPUT_MISMATCH)));
    }
  }

  // The worker's Dolby Vision conversion failure repeats in every attempt with the same decision
  // (streamarr/streamarr-server#651): a copy moves to the copy with Dolby Vision stripped, and any
  // other decision ends with the typed failure.
  @ParameterizedTest
  @CsvSource({
    "REMUX,true",
    "AUDIO_TRANSCODE,true",
    "VIDEO_TRANSCODE,false",
    "FULL_TRANSCODE,false"
  })
  @DisplayName(
      "Should treat a Dolby Vision conversion failure as terminal when the decision copies or"
          + " encodes video")
  void shouldTreatADolbyVisionConversionFailureAsTerminalWhenTheDecisionCopiesOrEncodesVideo(
      TranscodeMode mode, boolean copiesVideo) throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().transcodeDecision(rig.decision(mode)).build());
      var job = worker.nextJob();
      worker.fail(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_DOLBY_VISION_CONVERSION_FAILED);
      worker.awaitProcessed(rig.server);

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
          .contains(TranscodeFailure.DOLBY_VISION_CONVERSION_FAILED);
      assertThat(events.getEventsOfType(DolbyVisionConversionFailedEvent.class))
          .isEqualTo(copiesVideo ? List.of(conversionEventFor(job)) : List.of());
      assertThat(events.getEventsOfType(TerminalTranscodeFailureEvent.class))
          .isEqualTo(
              copiesVideo
                  ? List.of()
                  : List.of(
                      terminalEventFor(job, TranscodeFailure.DOLBY_VISION_CONVERSION_FAILED)));
    }
  }

  // Every attempt of a copy cuts the same keyframe-merged segments, so each would exceed the cap
  // again; a new format attempt on the plain grid avoids it (ADR 0037, ADR 0052).
  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should treat a segment over the cap as terminal when the job copies the video")
  void shouldTreatASegmentOverTheCapAsTerminalWhenTheJobCopiesTheVideo(TranscodeMode mode)
      throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      var request =
          rig.requestBuilder()
              .transcodeDecision(rig.decision(mode))
              .mediaSegmentCount(3)
              .exactSegmentStarts(
                  Optional.of(
                      new ExactSegmentStarts(List.of(0L, 6_000L, 18_000L), new Fraction(1, 1_000))))
              .build();
      rig.session.setHandle(rig.executor.start(request));
      var job = worker.nextJob();
      worker.fail(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_SEGMENT_CAP_EXCEEDED);
      rig.awaitStopped();

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
          .contains(TranscodeFailure.SEGMENT_CAP_EXCEEDED);
      assertThat(events.getEventsOfType(TerminalTranscodeFailureEvent.class))
          .containsExactly(terminalEventFor(job, TranscodeFailure.SEGMENT_CAP_EXCEEDED));
      assertThat(rig.deliver()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThat(worker.jobs).hasSize(1);
    }
  }

  // A plain grid whose final slot merges into its last segment lists every segment's start, yet an
  // encode on it keeps the plain grid's ordinary recovery for a segment over the cap (ADR 0037).
  @Test
  @DisplayName(
      "Should start a replacement attempt when an encode on a plain grid with a merged last"
          + " segment passes the segment cap")
  void shouldStartAReplacementAttemptWhenAnEncodeOnAMergedPlainGridPassesTheSegmentCap()
      throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events, mergedPlainGridEncodeSession());
        var worker = new TestWorker(rig.server);
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      rig.streaming.lifecycle().startFirstListedVariant(rig.session);
      var failed = worker.nextJob();
      worker.fail(failed, JobAttemptFailure.JOB_ATTEMPT_FAILURE_SEGMENT_CAP_EXCEEDED);
      rig.awaitStopped();
      var delivery = tasks.submit(rig::deliver);
      var recovered = worker.nextJob();
      rig.segments.storeSegment(rig.session.getSessionId(), "init.mp4", new byte[] {1});
      rig.server.heldSegmentRequests().wakeSession(rig.session.getSessionId());

      assertThat(delivery.get(5, TimeUnit.SECONDS)).isInstanceOf(SegmentDelivery.Ready.class);
      assertThat(List.of(failed, recovered))
          .extracting(job -> job.getExecution().getMediaSegmentCount())
          .containsExactly(2, 2);
      assertThat(recovered.getDecision().getMode()).isEqualTo(TRANSCODE_MODE_FULL_TRANSCODE);
      assertThat(rig.executor.terminalFailure(rig.session.getSessionId())).isEmpty();
      assertThat(events.getEventsOfType(TerminalTranscodeFailureEvent.class)).isEmpty();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE", "VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName("Should treat an invalid specification as terminal when the job runs in any mode")
  void shouldTreatInvalidSpecificationAsTerminalWhenJobRunsInAnyMode(TranscodeMode mode)
      throws Exception {
    var events = new CapturingEventPublisher();
    try (var rig = new FailureRig(events);
        var worker = new TestWorker(rig.server)) {
      rig.executor.start(rig.requestBuilder().transcodeDecision(rig.decision(mode)).build());
      var job = worker.nextJob();
      worker.fail(job, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      worker.awaitProcessed(rig.server);

      assertThat(rig.executor.terminalFailure(rig.session.getSessionId()))
          .contains(TranscodeFailure.INVALID_SPECIFICATION);
      assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class)).isEmpty();
      assertThat(events.getEventsOfType(TerminalTranscodeFailureEvent.class))
          .containsExactly(terminalEventFor(job, TranscodeFailure.INVALID_SPECIFICATION));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "REMUX,JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION",
    "VIDEO_TRANSCODE,JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH",
    "VIDEO_TRANSCODE,JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION",
    "FULL_TRANSCODE,JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH"
  })
  @DisplayName("Should send no replacement attempt when a segment request follows a final failure")
  void shouldSendNoReplacementAttemptWhenSegmentRequestFollowsFinalFailure(
      TranscodeMode mode, JobAttemptFailure failure) throws Exception {
    try (var rig = new FailureRig(new CapturingEventPublisher());
        var worker = new TestWorker(rig.server)) {
      var request = rig.requestBuilder().transcodeDecision(rig.decision(mode)).build();
      rig.session.setHandle(rig.executor.start(request));
      worker.fail(worker.nextJob(), failure);
      rig.awaitStopped();

      assertThat(rig.deliver()).isInstanceOf(SegmentDelivery.Unrecoverable.class);
      assertThatThrownBy(() -> rig.executor.start(request)).isInstanceOf(TranscodeException.class);
      assertThat(worker.jobs).hasSize(1);
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

  // 13.5 s on the 6 s grid reaches 1.5 s into a third slot, which merges into the second segment.
  private static StreamSession mergedPlainGridEncodeSession() {
    var duration = Duration.ofMillis(13_500);
    return defaultSessionBuilder()
        .transcodeDecision(fullTranscodeDecision("h264"))
        .mediaProbe(
            defaultProbeBuilder()
                .duration(duration)
                .keyframes(keyframesEvery(Duration.ofSeconds(2), duration))
                .build())
        .build();
  }

  private static TerminalTranscodeFailureEvent terminalEventFor(
      VariantJob job, TranscodeFailure failure) {
    return TerminalTranscodeFailureEvent.builder()
        .streamSessionId(fromProto(job.getStreamSessionId()))
        .jobAttemptId(fromProto(job.getJobAttemptId()))
        .variantLabel(job.getVariant().getVariantLabel())
        .failure(failure)
        .build();
  }

  private static VideoCopyOutputMismatchEvent eventFor(VariantJob job) {
    return new VideoCopyOutputMismatchEvent(
        fromProto(job.getStreamSessionId()),
        fromProto(job.getJobAttemptId()),
        job.getVariant().getVariantLabel());
  }

  private static DolbyVisionConversionFailedEvent conversionEventFor(VariantJob job) {
    return new DolbyVisionConversionFailedEvent(
        fromProto(job.getStreamSessionId()),
        fromProto(job.getJobAttemptId()),
        job.getVariant().getVariantLabel());
  }

  private static final class FailureRig implements AutoCloseable {
    private final FakeSegmentStore segments = new FakeSegmentStore();
    private final FakeRuntimeStreamSessionRegistry registry =
        new FakeRuntimeStreamSessionRegistry();
    private final StreamSession session;
    private final WorkerSessionServer server;
    private final RemoteTranscodeExecutor executor;
    private final StreamingRigFixture.StreamingRig streaming;

    private FailureRig(ApplicationEventPublisher events) throws Exception {
      this(events, defaultSessionBuilder().build());
    }

    private FailureRig(ApplicationEventPublisher events, StreamSession session) throws Exception {
      this.session = session;
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

    private TranscodeDecision decision(TranscodeMode mode) {
      var original = session.getTranscodeDecision();
      return TranscodeDecision.builder()
          .transcodeMode(mode)
          .videoCodecFamily(original.videoCodecFamily())
          .audioDecision(original.audioDecision())
          .subtitleDecision(original.subtitleDecision())
          .videoSource(original.videoSource())
          .build();
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
    private int backgroundProbeCapacity = 1;

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
                              .addProbeVersions(PROBE_VERSION)
                              .addSourceNamespaceIds(toProto(SOURCE_NAMESPACE_ID))
                              .addVideoEncoders(
                                  softwareEncoder(
                                      VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264")))
                      .setAvailableSlots(2)
                      .setBackgroundProbeCapacity(backgroundProbeCapacity)
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

    // The server reads a session's messages in order, so once it reports the capacity that this
    // message changes, it has read the failures before the message. The session sends nothing back
    // for a failure that it ignores, so no hook can replace the wait.
    private void awaitProcessed(WorkerSessionServer server) {
      var changed =
          server.probeCapacity(SOURCE_NAMESPACE_ID, PROBE_VERSION).backgroundProbeCapacity() + 1;
      backgroundProbeCapacity++;
      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setProbeCapacityChanged(
                  ProbeCapacityChanged.newBuilder()
                      .setBackgroundProbeCapacity(backgroundProbeCapacity)
                      .setKeyframePacketScanCapacity(1))
              .build());
      await()
          .atMost(5, TimeUnit.SECONDS)
          .until(
              () ->
                  server.probeCapacity(SOURCE_NAMESPACE_ID, PROBE_VERSION).backgroundProbeCapacity()
                      == changed);
    }

    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
