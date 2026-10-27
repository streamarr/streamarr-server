package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.hardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.streamCopyDecision;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.fakes.BlockingSegmentStore;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.JobAttemptStopped;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.TranscodeDecision;
import com.streamarr.transcode.v1.TranscodeMode;
import com.streamarr.transcode.v1.UploadSegmentRequest;
import com.streamarr.transcode.v1.UploadSegmentResponse;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.v1.VariantSpec;
import com.streamarr.transcode.v1.VideoCodecFamily;
import com.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.v1.VideoEncoderBackend;
import com.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.v1.WorkerCapabilities;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.v1.WorkerVerifiedVideoEncoder;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Session gRPC Service Tests")
class WorkerSessionGrpcServiceTest {

  // worker_session.proto bounds the keyframe positions of one probe attempt to 1,048,576 entries.
  private static final int MAXIMUM_KEYFRAME_POSITIONS_PER_PROBE = 1_048_576;
  // worker_session.proto bounds each list of a keyframe positions part to 4,096 entries.
  private static final int MAXIMUM_ENTRIES_PER_PART = 4_096;
  private static final String ENCODER_BACKEND_MISMATCH_METRIC =
      "streamarr.streaming.encoder_backend_mismatches";
  private static final WorkerVerifiedVideoEncoder LIBX264 =
      softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "libx264");

  @Test
  @DisplayName(
      "Should complete a probe when its result arrives on the authenticated worker session")
  void shouldCompleteProbeWhenItsResultArrivesOnAuthenticatedWorkerSession() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var session = workerSession(service, workerId, new IgnoringResponseObserver());
    var registration = registration(worker(workerId), sourceNamespaceId).toBuilder();
    registration
        .setBackgroundProbeCapacity(1)
        .setKeyframePacketScanCapacity(1)
        .getCapabilitiesBuilder()
        .addProbeVersions(1);
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setSource(variantJob(sourceNamespaceId).getSource())
            .build();
    var attempt = dispatched(registry.dispatchProbe(request));
    var result =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(1)
            .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
            .build();

    session.onNext(EstablishWorkerSessionRequest.newBuilder().setProbeResult(result).build());

    assertThat(attempt.get(1, TimeUnit.SECONDS).result()).isEqualTo(result);
  }

  @Test
  @DisplayName(
      "Should dispatch probes against the changed probe capacity when the worker session reports a"
          + " new capacity")
  void shouldDispatchProbesAgainstTheChangedProbeCapacityWhenTheWorkerSessionReportsANewCapacity()
      throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var session = workerSession(service, workerId, new IgnoringResponseObserver());
    var registration = registration(worker(workerId), sourceNamespaceId).toBuilder();
    registration
        .setBackgroundProbeCapacity(1)
        .setKeyframePacketScanCapacity(1)
        .getCapabilitiesBuilder()
        .addProbeVersions(1);
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());

    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeCapacityChanged(
                ProbeCapacityChanged.newBuilder()
                    .setBackgroundProbeCapacity(3)
                    .setKeyframePacketScanCapacity(1))
            .build());

    assertThat(registry.probeCapacity(sourceNamespaceId, 1).backgroundProbeCapacity()).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "Should report keyframe positions parts with the probe result when a worker sends them before the result")
  void shouldReportKeyframePositionsPartsWithProbeResultWhenWorkerSendsThemBeforeResult()
      throws Exception {
    var probe = SessionProbe.dispatch();
    var first = keyframePositionsPart(probe.request(), 2);
    var second = keyframePositionsPart(probe.request(), 1);

    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeKeyframePositionsPart(first));
    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeKeyframePositionsPart(second));
    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(probe.result()));

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS))
        .isEqualTo(
            new ProbeAttemptReport(
                probe.result(), List.of(keyframePositionsPart(probe.request(), 3))));
  }

  @Test
  @DisplayName(
      "Should report the same keyframe positions when a worker sends them in 1,025 single-entry parts")
  void shouldReportSameKeyframePositionsWhenWorkerSendsThemIn1025SingleEntryParts()
      throws Exception {
    var onePart = SessionProbe.dispatch();
    onePart.send(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeKeyframePositionsPart(keyframePositionsPart(onePart.request(), 1_025)));
    onePart.send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(onePart.result()));
    var singleEntryParts = SessionProbe.dispatch();
    var singleEntryPart = keyframePositionsPart(singleEntryParts.request(), 1);

    for (var sent = 0; sent < 1_025; sent++) {
      singleEntryParts.send(
          EstablishWorkerSessionRequest.newBuilder()
              .setProbeKeyframePositionsPart(singleEntryPart));
    }

    singleEntryParts.send(
        EstablishWorkerSessionRequest.newBuilder().setProbeResult(singleEntryParts.result()));

    assertThat(withoutAttemptIds(singleEntryParts.attempt().get(1, TimeUnit.SECONDS)))
        .isEqualTo(withoutAttemptIds(onePart.attempt().get(1, TimeUnit.SECONDS)));
  }

  @Test
  @DisplayName(
      "Should report no keyframe positions parts when a worker sends them for another probe attempt")
  void shouldReportNoKeyframePositionsPartsWhenWorkerSendsThemForAnotherProbeAttempt()
      throws Exception {
    var probe = SessionProbe.dispatch();
    var otherAttempt =
        probe.request().toBuilder().setProbeAttemptId(toProto(UUID.randomUUID())).build();

    probe.send(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeKeyframePositionsPart(keyframePositionsPart(otherAttempt, 1)));
    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(probe.result()));

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS).keyframePositionsParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail a probe when a worker sends more keyframe positions than one probe allows")
  void shouldFailProbeWhenWorkerSendsMoreKeyframePositionsThanOneProbeAllows() throws Exception {
    var probe = SessionProbe.dispatch();
    var fullPart = keyframePositionsPart(probe.request(), MAXIMUM_ENTRIES_PER_PART);

    for (var sent = 0;
        sent < MAXIMUM_KEYFRAME_POSITIONS_PER_PROBE / MAXIMUM_ENTRIES_PER_PART;
        sent++) {
      probe.send(
          EstablishWorkerSessionRequest.newBuilder().setProbeKeyframePositionsPart(fullPart));
    }

    probe.send(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeKeyframePositionsPart(keyframePositionsPart(probe.request(), 1)));

    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(probe.result()));

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @ParameterizedTest
  @CsvSource({"4097,0", "0,4097"})
  @DisplayName(
      "Should fail a probe when a keyframe positions part holds more entries than the contract allows")
  void shouldFailProbeWhenKeyframePositionsPartHoldsMoreEntriesThanContractAllows(
      int presentationTimes, int decodeTimes) throws Exception {
    var probe = SessionProbe.dispatch();
    var part = ProbeKeyframePositionsPart.newBuilder().setProbeAttemptId(probe.attemptId());
    LongStream.range(0, presentationTimes).forEach(part::addPresentationTimeDeltas);
    LongStream.range(0, decodeTimes).forEach(part::addDecodeTimeDeltas);

    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeKeyframePositionsPart(part));
    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(probe.result()));

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @Test
  @DisplayName("Should fail a probe when the lists of one keyframe positions part differ in length")
  void shouldFailProbeWhenListsOfOneKeyframePositionsPartDifferInLength() throws Exception {
    var probe = SessionProbe.dispatch();
    var longerPresentationTimes =
        ProbeKeyframePositionsPart.newBuilder()
            .setProbeAttemptId(probe.attemptId())
            .addPresentationTimeDeltas(90_000)
            .addPresentationTimeDeltas(90_000)
            .addDecodeTimeDeltas(90_000);
    var longerDecodeTimes =
        ProbeKeyframePositionsPart.newBuilder()
            .setProbeAttemptId(probe.attemptId())
            .addPresentationTimeDeltas(90_000)
            .addDecodeTimeDeltas(90_000)
            .addDecodeTimeDeltas(90_000);

    probe.send(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeKeyframePositionsPart(longerPresentationTimes));
    probe.send(
        EstablishWorkerSessionRequest.newBuilder()
            .setProbeKeyframePositionsPart(longerDecodeTimes));
    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(probe.result()));

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  private static List<ProbeKeyframePositionsPart> withoutAttemptIds(ProbeAttemptReport report) {
    return report.keyframePositionsParts().stream()
        .map(part -> part.toBuilder().clearProbeAttemptId().build())
        .toList();
  }

  private static ProbeKeyframePositionsPart keyframePositionsPart(
      ProbeRequest request, int keyframes) {
    var part =
        ProbeKeyframePositionsPart.newBuilder().setProbeAttemptId(request.getProbeAttemptId());
    for (var keyframe = 0; keyframe < keyframes; keyframe++) {
      part.addPresentationTimeDeltas(90_000).addDecodeTimeDeltas(90_000);
    }

    return part.build();
  }

  private record SessionProbe(
      StreamObserver<EstablishWorkerSessionRequest> session,
      ProbeRequest request,
      Future<ProbeAttemptReport> attempt) {

    private static SessionProbe dispatch() throws Exception {
      var workerId = UUID.randomUUID();
      var sourceNamespaceId = UUID.randomUUID();
      var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
      var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
      var session = workerSession(service, workerId, new IgnoringResponseObserver());
      var registration = registration(worker(workerId), sourceNamespaceId).toBuilder();
      registration
          .setBackgroundProbeCapacity(1)
          .setKeyframePacketScanCapacity(1)
          .getCapabilitiesBuilder()
          .addProbeVersions(1);
      session.onNext(
          EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
      var request =
          ProbeRequest.newBuilder()
              .setProbeAttemptId(toProto(UUID.randomUUID()))
              .setProbeVersion(1)
              .setSource(variantJob(sourceNamespaceId).getSource())
              .build();
      return new SessionProbe(session, request, dispatched(registry.dispatchProbe(request)));
    }

    private Uuid attemptId() {
      return request.getProbeAttemptId();
    }

    private ProbeAttemptResult result() {
      return ProbeAttemptResult.newBuilder()
          .setProbeAttemptId(request.getProbeAttemptId())
          .setProbeVersion(1)
          .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
          .build();
    }

    private void send(EstablishWorkerSessionRequest.Builder event) {
      session.onNext(event.build());
    }
  }

  @Test
  @DisplayName("Should retry registration when sending the acceptance response fails")
  void shouldRetryRegistrationWhenSendingAcceptanceResponseFails() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var session = workerSession(service, workerId, new FailingOnceResponseObserver());
    var registration =
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build();

    assertThatThrownBy(() -> session.onNext(registration))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Simulated response delivery failure");
    session.onNext(registration);

    assertThat(registry.hasConnectedWorker(sourceNamespaceId)).isTrue();
  }

  @Test
  @DisplayName("Should stop running the variant when a worker fails its job attempt")
  void shouldStopRunningVariantWhenWorkerFailsJobAttempt() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var session = workerSession(service, workerId, new IgnoringResponseObserver());
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    var streamSessionId = fromProto(job.getStreamSessionId());
    var variantLabel = job.getVariant().getVariantLabel();
    assertThat(registry.isRunning(streamSessionId, variantLabel)).isTrue();

    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
            .build());

    assertThat(registry.isRunning(streamSessionId, variantLabel)).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep the worker session and its running job when a worker reports results for"
          + " unknown job attempts")
  void shouldKeepWorkerSessionAndRunningJobWhenWorkerReportsResultsForUnknownJobAttempts()
      throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var session = workerSession(service, workerId, new IgnoringResponseObserver());
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    var streamSessionId = fromProto(job.getStreamSessionId());

    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptCompleted(
                JobAttemptCompleted.newBuilder().setJobAttemptId(toProto(UUID.randomUUID())))
            .build());
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptStopped(
                JobAttemptStopped.newBuilder().setJobAttemptId(toProto(UUID.randomUUID())))
            .build());

    assertThat(registry.hasConnectedWorker(sourceNamespaceId)).isTrue();
    assertThat(registry.isRunning(streamSessionId, job.getVariant().getVariantLabel())).isTrue();
    assertThat(registry.availableSlots(sourceNamespaceId)).isZero();
  }

  @Test
  @DisplayName(
      "Should keep the established session and its running job when a worker repeats"
          + " registration")
  void shouldKeepEstablishedSessionAndRunningJobWhenWorkerRepeatsRegistration() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var session = workerSession(service, workerId, new IgnoringResponseObserver());
    var registration =
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build();
    session.onNext(registration);
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    var streamSessionId = fromProto(job.getStreamSessionId());
    var establishedWorkers = registry.eligibleWorkers(sourceNamespaceId, streamCopyDecision());

    session.onNext(registration);

    // A re-registration would mint a new worker session and abandon the job it was running.
    assertThat(registry.eligibleWorkers(sourceNamespaceId, streamCopyDecision()))
        .isEqualTo(establishedWorkers);
    assertThat(registry.isRunning(streamSessionId, job.getVariant().getVariantLabel())).isTrue();
  }

  @Test
  @DisplayName("Should reject a registration when it advertises two encoders for one codec family")
  void shouldRejectRegistrationWhenItAdvertisesTwoEncodersForOneCodecFamily() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var responses = new TerminalErrorObserver();
    var session = workerSession(service, workerId, responses);
    var registration = registration(worker(workerId), sourceNamespaceId).toBuilder();
    registration
        .getCapabilitiesBuilder()
        .addVideoEncoders(hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc"))
        .addVideoEncoders(LIBX264);

    session.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());

    assertThat(responses.error).isNotNull();
    assertThat(Status.fromThrowable(responses.error).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(registry.hasConnectedWorker(sourceNamespaceId)).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(ReportedEncoderBackend.class)
  @DisplayName(
      "Should stop a job attempt when its start report names an encoder backend other than the pinned one")
  void shouldStopJobAttemptWhenItsStartReportNamesEncoderBackendOtherThanPinnedOne(
      ReportedEncoderBackend reported) throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var started = startedEncode(meterRegistry);

    started.session().onNext(jobAttemptStarted(started.job(), reported.backend()));

    assertThat(started.isRunning()).isFalse();
    assertThat(started.responses())
        .filteredOn(EstablishWorkerSessionResponse::hasStopVariant)
        .singleElement()
        .extracting(response -> response.getStopVariant().getJobAttemptId())
        .isEqualTo(started.job().getJobAttemptId());
    assertThat(meterRegistry.get(ENCODER_BACKEND_MISMATCH_METRIC).counter().count()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should send no further encode of the codec family to a worker whose start report named another encoder backend")
  void shouldSendNoFurtherEncodeOfCodecFamilyToWorkerWhoseStartReportNamedAnotherEncoderBackend()
      throws Exception {
    var started = startedEncode(new SimpleMeterRegistry());
    var nextEncode =
        started.job().toBuilder()
            .setStreamSessionId(toProto(UUID.randomUUID()))
            .setJobAttemptId(toProto(UUID.randomUUID()))
            .build();

    started
        .session()
        .onNext(jobAttemptStarted(started.job(), ReportedEncoderBackend.DIFFERENT.backend()));

    assertThat(started.registry().dispatch(nextEncode)).isEmpty();
    assertThat(started.responses()).noneMatch(response -> isStartOf(response, nextEncode));
  }

  @Test
  @DisplayName(
      "Should store no initialization segment that an encode attempt uploads before its start report names another encoder backend")
  void
      shouldStoreNoInitializationSegmentThatEncodeAttemptUploadsBeforeItsStartReportNamesAnotherBackend()
          throws Exception {
    var started =
        startedEncode(
            WorkerSessionServerConfiguration.builder()
                .startReportTimeout(Duration.ofMillis(100))
                .build(),
            new SimpleMeterRegistry());
    var response = new RecordingUploadResponseObserver();

    started.uploadInitializationSegment(response);
    started
        .session()
        .onNext(jobAttemptStarted(started.job(), ReportedEncoderBackend.DIFFERENT.backend()));

    assertThat(response.error()).isNotNull();
    assertThat(Status.fromThrowable(response.error()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(started.hasStoredInitializationSegment()).isFalse();
    assertThat(started.isRunning()).isFalse();
  }

  @Test
  @DisplayName(
      "Should keep a job attempt running when its start report names the pinned encoder backend")
  void shouldKeepJobAttemptRunningWhenItsStartReportNamesPinnedEncoderBackend() throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var started = startedEncode(meterRegistry);

    started.session().onNext(jobAttemptStarted(started.job(), Optional.of(LIBX264.getBackend())));

    assertThat(started.isRunning()).isTrue();
    assertThat(meterRegistry.get(ENCODER_BACKEND_MISMATCH_METRIC).counter().count()).isZero();
  }

  @Test
  @DisplayName("Should keep a stream copy running when its start report names no encoder backend")
  void shouldKeepStreamCopyRunningWhenItsStartReportNamesNoEncoderBackend() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var session = workerSession(service, workerId, new IgnoringResponseObserver());
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId).toBuilder().setDecision(streamCopyDecision()).build();
    assertThat(registry.dispatch(job)).isPresent();

    session.onNext(jobAttemptStarted(job, Optional.empty()));

    assertThat(
            registry.isRunning(
                fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
        .isTrue();
  }

  @Test
  @DisplayName("Should reject an upload when no authenticated worker identity is present")
  void shouldRejectUploadWhenNoAuthenticatedWorkerIdentityIsPresent() {
    var service =
        new WorkerSessionGrpcService(
            LiveWorkerConnectionRegistryFixture.defaultRegistry(), new FakeSegmentStore());
    var response = new RecordingUploadResponseObserver();

    var rejectedUpload = service.uploadSegment(response);
    rejectedUpload.onNext(UploadSegmentRequest.getDefaultInstance());
    rejectedUpload.onError(Status.CANCELLED.asRuntimeException());
    rejectedUpload.onCompleted();

    assertThat(response.error()).isNotNull();
    assertThat(Status.fromThrowable(response.error()).getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
    assertThat(response.errorCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("Should reject an upload when the global concurrent upload limit is exhausted")
  void shouldRejectUploadWhenGlobalConcurrentUploadLimitIsExhausted() throws Exception {
    var service =
        new WorkerSessionGrpcService(
            LiveWorkerConnectionRegistryFixture.defaultRegistry(), new FakeSegmentStore());
    var uploads = new ArrayList<StreamObserver<UploadSegmentRequest>>();
    // A distinct worker per upload, so the global ceiling is what rejects rather than any single
    // worker's allowance.
    for (var index = 0; index < 32; index++) {
      uploads.add(upload(service, UUID.randomUUID(), new RecordingUploadResponseObserver()));
    }
    var rejected = new RecordingUploadResponseObserver();

    var ignored = upload(service, UUID.randomUUID(), rejected);

    assertThat(rejected.error()).isNotNull();
    assertThat(Status.fromThrowable(rejected.error()).getCode())
        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    ignored.onNext(UploadSegmentRequest.getDefaultInstance());
    ignored.onError(Status.CANCELLED.asRuntimeException());
    ignored.onCompleted();

    uploads.removeFirst().onError(Status.CANCELLED.asRuntimeException());
    var resumed = new RecordingUploadResponseObserver();
    var resumedUpload = upload(service, UUID.randomUUID(), resumed);

    assertThat(resumed.error()).isNull();
    resumedUpload.onError(Status.CANCELLED.asRuntimeException());
    resumedUpload.onNext(UploadSegmentRequest.getDefaultInstance());
    resumedUpload.onError(Status.CANCELLED.asRuntimeException());
    uploads.forEach(upload -> upload.onError(Status.CANCELLED.asRuntimeException()));
  }

  @Test
  @DisplayName("Should reject an upload when the global declared byte budget is exhausted")
  void shouldRejectUploadWhenGlobalDeclaredByteBudgetIsExhausted() throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var workerSessionId =
        registry.register(
            workerId, registration(worker, sourceNamespaceId), new IgnoringResponseObserver());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var metadata =
        metadataBuilder(workerSessionId, worker, job)
            .setContentLengthBytes(128L * 1024 * 1024)
            .build();
    var uploads = new ArrayList<StreamObserver<UploadSegmentRequest>>();
    for (var index = 0; index < 4; index++) {
      var response = new RecordingUploadResponseObserver();
      var upload = upload(service, workerId, response);
      upload.onNext(UploadSegmentRequest.newBuilder().setMetadata(metadata).build());
      assertThat(response.error()).isNull();
      uploads.add(upload);
    }
    var rejected = new RecordingUploadResponseObserver();
    var excess = upload(service, workerId, rejected);

    excess.onNext(UploadSegmentRequest.newBuilder().setMetadata(metadata).build());

    assertThat(rejected.error()).isNotNull();
    assertThat(Status.fromThrowable(rejected.error()).getCode())
        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);

    uploads.removeFirst().onError(Status.CANCELLED.asRuntimeException());
    var resumed = new RecordingUploadResponseObserver();
    var resumedUpload = upload(service, workerId, resumed);
    resumedUpload.onNext(UploadSegmentRequest.newBuilder().setMetadata(metadata).build());

    assertThat(resumed.error()).isNull();
    resumedUpload.onError(Status.CANCELLED.asRuntimeException());
    uploads.forEach(upload -> upload.onError(Status.CANCELLED.asRuntimeException()));
  }

  @ParameterizedTest
  @ValueSource(ints = {64 * 1024 + 4096, 16 * 1024 * 1024 + 1, 128 * 1024 * 1024})
  @DisplayName("Should preserve every byte when a segment arrives in multiple frames")
  void shouldPreserveEveryByteWhenSegmentArrivesInMultipleFrames(int segmentBytes)
      throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var workerSessionId =
        registry.register(
            workerId, registration(worker, sourceNamespaceId), new IgnoringResponseObserver());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    var segmentStore = new FakeSegmentStore();
    var service = new WorkerSessionGrpcService(registry, segmentStore);
    var response = new SuccessfulUploadResponseObserver();
    var segmentData = new byte[segmentBytes];
    Arrays.fill(segmentData, (byte) 1);
    Arrays.fill(segmentData, segmentBytes - 4096, segmentBytes, (byte) 2);
    var upload = upload(service, workerId, response);

    upload.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(
                metadataBuilder(workerSessionId, worker, job)
                    .setContentLengthBytes(segmentData.length))
            .build());
    for (var offset = 0; offset < segmentBytes; offset += 64 * 1024) {
      var length = Math.min(64 * 1024, segmentBytes - offset);
      upload.onNext(
          UploadSegmentRequest.newBuilder()
              .setData(ByteString.copyFrom(segmentData, offset, length))
              .build());
    }

    upload.onCompleted();

    assertThat(response.error()).isNull();
    assertThat(response.completed()).isTrue();
    assertThat(response.response().getAcceptedLengthBytes()).isEqualTo(segmentData.length);
    assertThat(
            segmentStore.readSegment(
                fromProto(job.getStreamSessionId()),
                job.getVariant().getVariantLabel() + "/segment0.m4s"))
        .isEqualTo(segmentData);
  }

  @Test
  @DisplayName("Should reject an upload when ownership is lost during publication")
  void shouldRejectUploadWhenOwnershipIsLostDuringPublication() throws Exception {
    var segmentStore = new BlockingSegmentStore();

    var result = uploadThatLosesOwnership(segmentStore);

    assertThat(result.response().error()).isNotNull();
    assertThat(Status.fromThrowable(result.response().error()).getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
    assertThat(segmentStore.segmentExists(result.streamSessionId(), result.segmentName()))
        .isFalse();
  }

  @Test
  @DisplayName("Should report one storage failure when rejected upload cleanup fails")
  void shouldReportOneStorageFailureWhenRejectedUploadCleanupFails() throws Exception {
    var segmentStore = new CloseFailingBlockingSegmentStore();

    var result = uploadThatLosesOwnership(segmentStore);

    assertThat(result.response().errorCount()).isEqualTo(1);
    assertThat(Status.fromThrowable(result.response().error()).getCode())
        .isEqualTo(Status.Code.INTERNAL);
  }

  private static OwnershipLossResult uploadThatLosesOwnership(BlockingSegmentStore segmentStore)
      throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var registry = LiveWorkerConnectionRegistryFixture.defaultRegistry();
    var workerSessionId =
        registry.register(
            workerId, registration(worker, sourceNamespaceId), new IgnoringResponseObserver());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    var service = new WorkerSessionGrpcService(registry, segmentStore);
    var response = new RecordingUploadResponseObserver();
    var segmentData = ByteString.copyFromUtf8("segment");
    var upload = upload(service, workerId, response);
    upload.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(
                metadataBuilder(workerSessionId, worker, job)
                    .setContentLengthBytes(segmentData.size()))
            .build());
    upload.onNext(UploadSegmentRequest.newBuilder().setData(segmentData).build());

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var publication = executor.submit(upload::onCompleted);
      try {
        assertThat(segmentStore.awaitPreparation(Duration.ofSeconds(5))).isTrue();
        registry.disconnect(workerId, workerSessionId);
        segmentStore.continuePreparation();
        publication.get(5, TimeUnit.SECONDS);
      } finally {
        segmentStore.continuePreparation();
      }
    }

    return new OwnershipLossResult(
        response,
        fromProto(job.getStreamSessionId()),
        job.getVariant().getVariantLabel() + "/segment0.m4s");
  }

  private record OwnershipLossResult(
      RecordingUploadResponseObserver response, UUID streamSessionId, String segmentName) {}

  private enum ReportedEncoderBackend {
    DIFFERENT(
        Optional.of(
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc").getBackend())),
    ABSENT(Optional.empty());

    private final Optional<VideoEncoderBackend> backend;

    ReportedEncoderBackend(Optional<VideoEncoderBackend> backend) {
      this.backend = backend;
    }

    private Optional<VideoEncoderBackend> backend() {
      return backend;
    }
  }

  private static StartedEncode startedEncode(SimpleMeterRegistry meterRegistry) throws Exception {
    return startedEncode(WorkerSessionServerConfiguration.builder().build(), meterRegistry);
  }

  private static StartedEncode startedEncode(
      WorkerSessionServerConfiguration configuration, SimpleMeterRegistry meterRegistry)
      throws Exception {
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(configuration)
            .meterRegistry(meterRegistry)
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .build();
    var segmentStore = new FakeSegmentStore();
    var service = new WorkerSessionGrpcService(registry, segmentStore);
    var responses = new CopyOnWriteArrayList<EstablishWorkerSessionResponse>();
    var session = workerSession(service, workerId, new CollectingResponseObserver(responses));
    var registration = registration(worker(workerId), sourceNamespaceId).toBuilder();
    registration.getCapabilitiesBuilder().addVideoEncoders(LIBX264);
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
    var job =
        variantJob(sourceNamespaceId).toBuilder()
            .setDecision(
                TranscodeDecision.newBuilder()
                    .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                    .setVideo(
                        VideoDecision.newBuilder()
                            .setMode(VideoMode.VIDEO_MODE_ENCODE)
                            .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)))
            .build();
    assertThat(registry.dispatch(job)).isPresent();
    return StartedEncode.builder()
        .registry(registry)
        .service(service)
        .segmentStore(segmentStore)
        .worker(registration.getWorker())
        .workerSessionId(fromProto(responses.getFirst().getSessionAccepted().getWorkerSessionId()))
        .session(session)
        .job(job)
        .responses(responses)
        .build();
  }

  @Builder
  private record StartedEncode(
      LiveWorkerConnectionRegistry registry,
      WorkerSessionGrpcService service,
      FakeSegmentStore segmentStore,
      WorkerIdentity worker,
      UUID workerSessionId,
      StreamObserver<EstablishWorkerSessionRequest> session,
      VariantJob job,
      List<EstablishWorkerSessionResponse> responses) {

    private boolean isRunning() {
      return registry.isRunning(
          fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel());
    }

    private void uploadInitializationSegment(StreamObserver<UploadSegmentResponse> response)
        throws Exception {
      var segmentData = ByteString.copyFromUtf8("init");
      var upload = upload(service, fromProto(worker.getWorkerId()), response);
      upload.onNext(
          UploadSegmentRequest.newBuilder()
              .setMetadata(
                  metadataBuilder(workerSessionId, worker, job)
                      .setSegmentName(SegmentNames.INITIALIZATION_SEGMENT_NAME)
                      .setContentLengthBytes(segmentData.size()))
              .build());
      upload.onNext(UploadSegmentRequest.newBuilder().setData(segmentData).build());
      upload.onCompleted();
    }

    private boolean hasStoredInitializationSegment() {
      return segmentStore.segmentExists(
          fromProto(job.getStreamSessionId()),
          job.getVariant().getVariantLabel() + "/" + SegmentNames.INITIALIZATION_SEGMENT_NAME);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should ignore an output mismatch when its worker connection is no longer current")
  void shouldIgnoreOutputMismatchWhenWorkerConnectionIsNoLongerCurrent(boolean reconnected)
      throws Exception {
    var events = new CapturingEventPublisher();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(new SimpleMeterRegistry())
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .eventPublisher(events)
            .build();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var oldConnection = workerSession(service, workerId, new IgnoringResponseObserver());
    oldConnection.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId).toBuilder().setDecision(streamCopyDecision()).build();
    assertThat(registry.dispatch(job)).isPresent();
    oldConnection.onError(Status.CANCELLED.asRuntimeException());
    if (reconnected) {
      var current = workerSession(service, workerId, new IgnoringResponseObserver());
      current.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(registration(worker(workerId), sourceNamespaceId))
              .build());
      assertThat(registry.dispatch(job)).isPresent();
    }

    oldConnection.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH))
            .build());

    assertThat(registry.terminalFailure(fromProto(job.getStreamSessionId()))).isEmpty();
    assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class)).isEmpty();
    assertThat(
            registry.isRunning(
                fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
        .isEqualTo(reconnected);
  }

  @Test
  @DisplayName("Should ignore a delayed output mismatch when the worker registry is closed")
  void shouldIgnoreDelayedOutputMismatchWhenWorkerRegistryIsClosed() throws Exception {
    var events = new CapturingEventPublisher();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(new SimpleMeterRegistry())
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .eventPublisher(events)
            .build();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var connection = workerSession(service, workerId, new IgnoringResponseObserver());
    connection.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId).toBuilder().setDecision(streamCopyDecision()).build();
    assertThat(registry.dispatch(job)).isPresent();

    registry.close();
    connection.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH))
            .build());

    assertThat(registry.terminalFailure(fromProto(job.getStreamSessionId()))).isEmpty();
    assertThat(events.getEventsOfType(VideoCopyOutputMismatchEvent.class)).isEmpty();
    assertThat(registry.hasConnectedWorker(sourceNamespaceId)).isFalse();
  }

  private static boolean isStartOf(EstablishWorkerSessionResponse response, VariantJob job) {
    return response.hasStartVariant()
        && response.getStartVariant().getJob().getJobAttemptId().equals(job.getJobAttemptId());
  }

  private static EstablishWorkerSessionRequest jobAttemptStarted(
      VariantJob job, Optional<VideoEncoderBackend> backend) {
    var started = JobAttemptStarted.newBuilder().setJobAttemptId(job.getJobAttemptId());
    backend.ifPresent(started::setEncoderBackend);
    return EstablishWorkerSessionRequest.newBuilder().setJobAttemptStarted(started).build();
  }

  private static StreamObserver<EstablishWorkerSessionRequest> workerSession(
      WorkerSessionGrpcService service,
      UUID workerId,
      StreamObserver<EstablishWorkerSessionResponse> responseObserver)
      throws Exception {
    return Context.current()
        .withValue(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, workerId)
        .call(() -> service.establishWorkerSession(responseObserver));
  }

  private static StreamObserver<UploadSegmentRequest> upload(
      WorkerSessionGrpcService service,
      UUID workerId,
      StreamObserver<UploadSegmentResponse> responseObserver)
      throws Exception {
    return Context.current()
        .withValue(WorkerIdentityServerInterceptor.AUTHENTICATED_WORKER_ID, workerId)
        .call(() -> service.uploadSegment(responseObserver));
  }

  private static WorkerIdentity worker(UUID workerId) {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(workerId))
        .setBootId(toProto(UUID.randomUUID()))
        .build();
  }

  private static WorkerRegistration registration(WorkerIdentity worker, UUID sourceNamespaceId) {
    return WorkerRegistration.newBuilder()
        .setWorker(worker)
        .setCapabilities(
            WorkerCapabilities.newBuilder().addSourceNamespaceIds(toProto(sourceNamespaceId)))
        .setAvailableSlots(1)
        .build();
  }

  private static VariantJob variantJob(UUID sourceNamespaceId) {
    return VariantJob.newBuilder()
        .setStreamSessionId(toProto(UUID.randomUUID()))
        .setJobId(toProto(UUID.randomUUID()))
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .setSource(
            MediaSourceRef.newBuilder()
                .setSourceNamespaceId(toProto(sourceNamespaceId))
                .setRelativeKey("movie.mkv"))
        .setVariant(VariantSpec.newBuilder().setVariantLabel("720p"))
        .build();
  }

  private static SegmentUploadMetadata.Builder metadataBuilder(
      UUID workerSessionId, WorkerIdentity worker, VariantJob job) {
    return SegmentUploadMetadata.newBuilder()
        .setWorkerSessionId(toProto(workerSessionId))
        .setWorker(worker)
        .setStreamSessionId(job.getStreamSessionId())
        .setJobId(job.getJobId())
        .setJobAttemptId(job.getJobAttemptId())
        .setVariantLabel(job.getVariant().getVariantLabel())
        .setSegmentName("segment0.m4s")
        .setContentType(SegmentContentType.SEGMENT_CONTENT_TYPE_VIDEO_MP4);
  }

  private static final class CloseFailingBlockingSegmentStore extends BlockingSegmentStore {

    @Override
    public PreparedSegment prepareSegment(UUID sessionId, String segmentName, byte[] data) {
      var prepared = super.prepareSegment(sessionId, segmentName, data);
      return new PreparedSegment() {
        @Override
        public SegmentPublication publish() {
          return prepared.publish();
        }

        @Override
        public void close() {
          prepared.close();
          throw new IllegalStateException("Simulated prepared segment cleanup failure");
        }
      };
    }
  }

  private static final class RecordingUploadResponseObserver
      implements StreamObserver<UploadSegmentResponse> {

    private Throwable error;
    private int errorCount;

    @Override
    public void onNext(UploadSegmentResponse value) {
      throw new AssertionError("Rejected or cancelled upload should not receive a response");
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
      errorCount++;
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Rejected or cancelled upload should not complete successfully");
    }

    private Throwable error() {
      return error;
    }

    private int errorCount() {
      return errorCount;
    }
  }

  private static final class SuccessfulUploadResponseObserver
      implements StreamObserver<UploadSegmentResponse> {

    private UploadSegmentResponse response;
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(UploadSegmentResponse value) {
      response = value;
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }

    private UploadSegmentResponse response() {
      return response;
    }

    private Throwable error() {
      return error;
    }

    private boolean completed() {
      return completed;
    }
  }

  private record CollectingResponseObserver(List<EstablishWorkerSessionResponse> responses)
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("Registered worker should remain connected", throwable);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Registered worker should remain connected");
    }
  }

  private static final class TerminalErrorObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private Throwable error;

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      // This failure-path test reads only the terminal error.
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("A rejected registration ends with an error");
    }
  }

  private static final class IgnoringResponseObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      assertThat(value.getCommandCase())
          .isIn(
              EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED,
              EstablishWorkerSessionResponse.CommandCase.START_PROBE,
              EstablishWorkerSessionResponse.CommandCase.START_VARIANT);
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("Registered worker should remain connected", throwable);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Registered worker should remain connected");
    }
  }

  private static final class FailingOnceResponseObserver
      implements StreamObserver<EstablishWorkerSessionResponse> {

    private boolean failed;

    @Override
    public void onNext(EstablishWorkerSessionResponse value) {
      if (!failed) {
        failed = true;
        throw new IllegalStateException("Simulated response delivery failure");
      }
      assertThat(value.getCommandCase())
          .isEqualTo(EstablishWorkerSessionResponse.CommandCase.SESSION_ACCEPTED);
    }

    @Override
    public void onError(Throwable throwable) {
      throw new AssertionError("Registration retry should remain connected", throwable);
    }

    @Override
    public void onCompleted() {
      throw new AssertionError("Registration retry should remain connected");
    }
  }
}
