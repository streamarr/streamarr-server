package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.fixtures.RemoteWorkerFixtures.dispatched;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.hardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.keyframeVerifiedHardwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.softwareEncoder;
import static com.streamarr.server.fixtures.RemoteWorkerFixtures.streamCopyDecision;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.fromProto;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.protobuf.ByteString;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.fakes.BlockingSegmentStore;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeSegmentStore;
import com.streamarr.server.services.streaming.HeldSegmentRequests;
import com.streamarr.server.services.streaming.SegmentNames;
import com.streamarr.server.services.streaming.SegmentPublication;
import com.streamarr.server.services.streaming.WorkerVerifiedCodecFamilies;
import com.streamarr.server.services.streaming.events.JobAttemptCompletedEvent;
import com.streamarr.server.services.streaming.events.JobAttemptProgressedEvent;
import com.streamarr.server.services.streaming.events.VideoCopyOutputMismatchEvent;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.JobAttemptCompleted;
import com.streamarr.transcode.v1.JobAttemptFailed;
import com.streamarr.transcode.v1.JobAttemptFailure;
import com.streamarr.transcode.v1.JobAttemptProgress;
import com.streamarr.transcode.v1.JobAttemptStarted;
import com.streamarr.transcode.v1.JobAttemptStopped;
import com.streamarr.transcode.v1.MediaSourceRef;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeCapacityChanged;
import com.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import com.streamarr.transcode.v1.SegmentContentType;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import com.streamarr.transcode.v1.StreamMeasurementSource;
import com.streamarr.transcode.v1.StreamMeasurements;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Worker Session gRPC Service Tests")
class WorkerSessionGrpcServiceTest {

  // worker_session.proto bounds the keyframe positions of one probe attempt to 1,048,576 entries.
  private static final int MAXIMUM_KEYFRAME_POSITIONS_PER_PROBE = 1_048_576;
  // worker_session.proto bounds each list of a keyframe positions part to 4,096 entries.
  private static final int MAXIMUM_ENTRIES_PER_PART = 4_096;
  // worker_session.proto bounds the measurement parts of one probe attempt to 8,388,608 entries.
  private static final int MAXIMUM_MEASUREMENT_ENTRIES_PER_PROBE = 8_388_608;
  // worker_session.proto bounds the streams that the measurement parts of one attempt name to
  // 1,024.
  private static final int MAXIMUM_MEASURED_STREAMS_PER_PROBE = 1_024;
  private static final int VIDEO_STREAM = 0;
  private static final int AUDIO_STREAM = 1;
  private static final String ENCODER_BACKEND_MISMATCH_METRIC =
      "streamarr.streaming.encoder_backend_mismatches";
  private static final String MISSING_ENCODER_METRIC = "streamarr.streaming.missing_encoder";
  private static final String UNKNOWN_JOB_ATTEMPT_REPORTS_METRIC =
      "streamarr.streaming.unknown_job_attempt_reports";
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

  @Test
  @DisplayName(
      "Should join each stream's measurement parts in order when a worker interleaves the streams")
  void shouldJoinEachStreamsMeasurementPartsInOrderWhenWorkerInterleavesStreams() throws Exception {
    var probe = SessionProbe.dispatch();

    probe.sendMeasurementPart(
        probe
            .measurementPart(VIDEO_STREAM)
            .addAllKeyframeIntervalBytes(List.of(1, 2))
            .addAllKeyframeIntervalPacketCounts(List.of(11, 12)));
    probe.sendMeasurementPart(
        probe
            .measurementPart(AUDIO_STREAM)
            .addKeyframeIntervalBytes(7)
            .addKeyframeIntervalPacketCounts(17));
    probe.sendMeasurementPart(
        probe
            .measurementPart(VIDEO_STREAM)
            .addKeyframeIntervalBytes(3)
            .addKeyframeIntervalPacketCounts(13));
    probe.sendMeasuredResult();

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS).streamMeasurementParts())
        .containsExactly(
            probe
                .measurementPart(VIDEO_STREAM)
                .addAllKeyframeIntervalBytes(List.of(1, 2, 3))
                .addAllKeyframeIntervalPacketCounts(List.of(11, 12, 13))
                .build(),
            probe
                .measurementPart(AUDIO_STREAM)
                .addKeyframeIntervalBytes(7)
                .addKeyframeIntervalPacketCounts(17)
                .build());
  }

  @Test
  @DisplayName(
      "Should report no measurement parts when a worker sends them for another probe attempt")
  void shouldReportNoMeasurementPartsWhenWorkerSendsThemForAnotherProbeAttempt() throws Exception {
    var probe = SessionProbe.dispatch();

    probe.sendMeasurementPart(
        withEntries(probe.measurementPart(VIDEO_STREAM), 1)
            .setProbeAttemptId(toProto(UUID.randomUUID())));
    probe.sendMeasuredResult();

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS).streamMeasurementParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should accept a measurement part when it holds exactly as many entries as one part allows")
  void shouldAcceptMeasurementPartWhenItHoldsExactlyAsManyEntriesAsOnePartAllows()
      throws Exception {
    var probe = SessionProbe.dispatch();

    probe.sendMeasurementPart(
        withEntries(probe.measurementPart(VIDEO_STREAM), MAXIMUM_ENTRIES_PER_PART));
    probe.sendMeasuredResult();

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS).streamMeasurementParts())
        .singleElement()
        .extracting(ProbeStreamMeasurementPart::getKeyframeIntervalBytesCount)
        .isEqualTo(MAXIMUM_ENTRIES_PER_PART);
  }

  @Test
  @DisplayName(
      "Should fail a probe when a measurement part holds more entries than one part allows")
  void shouldFailProbeWhenMeasurementPartHoldsMoreEntriesThanOnePartAllows() throws Exception {
    var probe = SessionProbe.dispatch();

    probe.sendMeasurementPart(
        withEntries(probe.measurementPart(VIDEO_STREAM), MAXIMUM_ENTRIES_PER_PART + 1));
    probe.sendMeasuredResult();

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @Test
  @DisplayName(
      "Should accept measurement parts when they hold exactly as many entries as one probe allows")
  void shouldAcceptMeasurementPartsWhenTheyHoldExactlyAsManyEntriesAsOneProbeAllows()
      throws Exception {
    var probe = SessionProbe.dispatch();

    sendFullMeasurementParts(probe, MAXIMUM_MEASUREMENT_ENTRIES_PER_PROBE);
    probe.sendMeasuredResult();

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS).streamMeasurementParts())
        .singleElement()
        .extracting(ProbeStreamMeasurementPart::getKeyframeIntervalBytesCount)
        .isEqualTo(MAXIMUM_MEASUREMENT_ENTRIES_PER_PROBE);
  }

  @Test
  @DisplayName(
      "Should fail a probe when a worker sends more measurement entries than one probe allows")
  void shouldFailProbeWhenWorkerSendsMoreMeasurementEntriesThanOneProbeAllows() throws Exception {
    var probe = SessionProbe.dispatch();

    sendFullMeasurementParts(probe, MAXIMUM_MEASUREMENT_ENTRIES_PER_PROBE);
    probe.sendMeasurementPart(withEntries(probe.measurementPart(AUDIO_STREAM), 1));
    probe.sendMeasuredResult();

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @Test
  @DisplayName(
      "Should fail a probe when a measurement part counts packets for other intervals than its"
          + " bytes")
  void shouldFailProbeWhenMeasurementPartCountsPacketsForOtherIntervalsThanItsBytes()
      throws Exception {
    var probe = SessionProbe.dispatch();

    probe.sendMeasurementPart(
        probe
            .measurementPart(VIDEO_STREAM)
            .addAllKeyframeIntervalBytes(List.of(1, 2))
            .addAllKeyframeIntervalPacketCounts(List.of(1, 1, 1)));
    probe.sendMeasuredResult();

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @Test
  @DisplayName("Should fail a probe when a measurement part holds no entries")
  void shouldFailProbeWhenMeasurementPartHoldsNoEntries() throws Exception {
    var probe = SessionProbe.dispatch();

    probe.sendMeasurementPart(probe.measurementPart(VIDEO_STREAM));
    probe.sendMeasuredResult();

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @Test
  @DisplayName(
      "Should accept measurement parts when they name exactly as many streams as one probe allows")
  void shouldAcceptMeasurementPartsWhenTheyNameExactlyAsManyStreamsAsOneProbeAllows()
      throws Exception {
    var probe = SessionProbe.dispatch();

    for (var streamIndex = 0; streamIndex < MAXIMUM_MEASURED_STREAMS_PER_PROBE; streamIndex++) {
      probe.sendMeasurementPart(withEntries(probe.measurementPart(streamIndex), 1));
    }

    probe.sendMeasurementPart(withEntries(probe.measurementPart(VIDEO_STREAM), 1));
    probe.sendMeasuredResult();

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS).streamMeasurementParts())
        .hasSize(MAXIMUM_MEASURED_STREAMS_PER_PROBE)
        .first()
        .extracting(ProbeStreamMeasurementPart::getKeyframeIntervalBytesCount)
        .isEqualTo(2);
  }

  @Test
  @DisplayName("Should fail a probe when measurement parts name more streams than one probe allows")
  void shouldFailProbeWhenMeasurementPartsNameMoreStreamsThanOneProbeAllows() throws Exception {
    var probe = SessionProbe.dispatch();

    for (var streamIndex = 0; streamIndex <= MAXIMUM_MEASURED_STREAMS_PER_PROBE; streamIndex++) {
      probe.sendMeasurementPart(withEntries(probe.measurementPart(streamIndex), 1));
    }

    probe.sendMeasuredResult();

    assertThatThrownBy(() -> probe.attempt().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(ProbeContractViolationException.class);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("measurementPartsOfUnmeasuredResult")
  @DisplayName("Should discard the measurement parts when the result reports no measurement")
  void shouldDiscardMeasurementPartsWhenResultReportsNoMeasurement(
      String parts, UnaryOperator<ProbeStreamMeasurementPart.Builder> fill) throws Exception {
    var probe = SessionProbe.dispatch();

    probe.sendMeasurementPart(fill.apply(probe.measurementPart(VIDEO_STREAM)));
    probe.send(EstablishWorkerSessionRequest.newBuilder().setProbeResult(probe.result()));

    assertThat(probe.attempt().get(1, TimeUnit.SECONDS).streamMeasurementParts()).isEmpty();
  }

  static Stream<Arguments> measurementPartsOfUnmeasuredResult() {
    return Stream.of(
        arguments(
            "a part that keeps the contract",
            (UnaryOperator<ProbeStreamMeasurementPart.Builder>) part -> withEntries(part, 1)),
        arguments(
            "a part that breaks the contract",
            UnaryOperator.<ProbeStreamMeasurementPart.Builder>identity()));
  }

  private static void sendFullMeasurementParts(SessionProbe probe, int entries) {
    for (var sent = 0; sent < entries / MAXIMUM_ENTRIES_PER_PART; sent++) {
      probe.sendMeasurementPart(
          withEntries(probe.measurementPart(VIDEO_STREAM), MAXIMUM_ENTRIES_PER_PART));
    }
  }

  /** Adds this many keyframe intervals to the part, each with no bytes and no packets. */
  private static ProbeStreamMeasurementPart.Builder withEntries(
      ProbeStreamMeasurementPart.Builder part, int entries) {
    var zeros = Collections.nCopies(entries, 0);
    return part.addAllKeyframeIntervalBytes(zeros).addAllKeyframeIntervalPacketCounts(zeros);
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

    private ProbeStreamMeasurementPart.Builder measurementPart(int streamIndex) {
      return ProbeStreamMeasurementPart.newBuilder()
          .setProbeAttemptId(attemptId())
          .setStreamIndex(streamIndex);
    }

    private void sendMeasurementPart(ProbeStreamMeasurementPart.Builder part) {
      send(EstablishWorkerSessionRequest.newBuilder().setProbeStreamMeasurementPart(part));
    }

    private void sendMeasuredResult() {
      var media =
          result().getMedia().toBuilder()
              .setStreamMeasurements(
                  StreamMeasurements.newBuilder()
                      .setSource(StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_SAMPLE_TABLE));
      send(
          EstablishWorkerSessionRequest.newBuilder()
              .setProbeResult(result().toBuilder().setMedia(media)));
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

  // ADR 0052: a request that the completion wakes must find the short copy that it showed.
  @Test
  @DisplayName(
      "Should publish the completion while the job attempt still runs when its worker reports it"
          + " completed")
  void shouldPublishTheCompletionWhileTheJobAttemptStillRunsWhenItsWorkerReportsItCompleted()
      throws Exception {
    var runningAtCompletion = new ArrayList<Boolean>();
    var completions = new ArrayList<JobAttemptCompletedEvent>();
    var registryHolder = new AtomicReference<LiveWorkerConnectionRegistry>();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(new SimpleMeterRegistry())
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .eventPublisher(
                event -> {
                  if (event instanceof JobAttemptCompletedEvent completion) {
                    completions.add(completion);
                    runningAtCompletion.add(
                        registryHolder
                            .get()
                            .isRunning(completion.streamSessionId(), completion.variantLabel()));
                  }
                })
            .build();
    registryHolder.set(registry);
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var session = workerSession(service, workerId, new IgnoringResponseObserver());
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();

    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptCompleted(
                JobAttemptCompleted.newBuilder().setJobAttemptId(job.getJobAttemptId()))
            .build());

    assertThat(completions)
        .containsExactly(
            JobAttemptCompletedEvent.builder()
                .streamSessionId(fromProto(job.getStreamSessionId()))
                .jobAttemptId(fromProto(job.getJobAttemptId()))
                .variantLabel(job.getVariant().getVariantLabel())
                .build());
    assertThat(runningAtCompletion).containsExactly(true);
    assertThat(
            registry.isRunning(
                fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
        .isFalse();
  }

  @Test
  @DisplayName(
      "Should publish no completion when a worker reports the completion of a job attempt that"
          + " the server stopped")
  void shouldPublishNoCompletionWhenAWorkerReportsTheCompletionOfAJobAttemptThatTheServerStopped()
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
    var session =
        workerSession(
            service, workerId, new CollectingResponseObserver(new CopyOnWriteArrayList<>()));
    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    assertThat(
            registry.stopVariant(
                fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
        .isTrue();

    session.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptCompleted(
                JobAttemptCompleted.newBuilder().setJobAttemptId(job.getJobAttemptId()))
            .build());

    assertThat(events.getEventsOfType(JobAttemptCompletedEvent.class)).isEmpty();
  }

  @Test
  @DisplayName(
      "Should count no unknown job attempt report when a worker reports the end of a job attempt"
          + " that the server stopped")
  void shouldCountNoUnknownJobAttemptReportWhenWorkerReportsEndOfJobAttemptThatServerStopped()
      throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var started = startedEncode(meterRegistry);
    var job = started.job();
    assertThat(
            started
                .registry()
                .stopVariant(
                    fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
        .isTrue();

    started
        .session()
        .onNext(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptStopped(
                    JobAttemptStopped.newBuilder().setJobAttemptId(job.getJobAttemptId()))
                .build());

    assertThat(meterRegistry.get(UNKNOWN_JOB_ATTEMPT_REPORTS_METRIC).counter().count()).isZero();
  }

  @Test
  @DisplayName(
      "Should count an unknown job attempt report when a worker reports the end of a job attempt"
          + " that the server neither runs nor stopped")
  void
      shouldCountUnknownJobAttemptReportWhenWorkerReportsEndOfJobAttemptServerNeitherRunsNorStopped()
          throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var started = startedEncode(meterRegistry);
    var job = started.job();
    started
        .registry()
        .stopVariant(fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel());
    var stopReport =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptStopped(
                JobAttemptStopped.newBuilder().setJobAttemptId(job.getJobAttemptId()))
            .build();
    started.session().onNext(stopReport);

    started.session().onNext(stopReport);
    started
        .session()
        .onNext(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptCompleted(
                    JobAttemptCompleted.newBuilder().setJobAttemptId(toProto(UUID.randomUUID())))
                .build());

    assertThat(meterRegistry.get(UNKNOWN_JOB_ATTEMPT_REPORTS_METRIC).counter().count())
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should count no unknown job attempt report when a worker reports the failure of a job"
          + " attempt that the server stopped")
  void shouldCountNoUnknownJobAttemptReportWhenWorkerReportsFailureOfJobAttemptThatServerStopped()
      throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var started = startedEncode(meterRegistry);
    var job = started.job();
    assertThat(
            started
                .registry()
                .stopVariant(
                    fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel()))
        .isTrue();

    started.session().onNext(failureReport(job.getJobAttemptId()));

    assertThat(meterRegistry.get(UNKNOWN_JOB_ATTEMPT_REPORTS_METRIC).counter().count()).isZero();
  }

  @Test
  @DisplayName(
      "Should count an unknown job attempt report when a worker reports the failure of a job"
          + " attempt that the server neither runs nor stopped")
  void
      shouldCountUnknownJobAttemptReportWhenWorkerReportsFailureOfJobAttemptServerNeitherRunsNorStopped()
          throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var started = startedEncode(meterRegistry);
    var job = started.job();
    started
        .registry()
        .stopVariant(fromProto(job.getStreamSessionId()), job.getVariant().getVariantLabel());
    var failureReport = failureReport(job.getJobAttemptId());
    started.session().onNext(failureReport);

    started.session().onNext(failureReport);
    started.session().onNext(failureReport(toProto(UUID.randomUUID())));

    assertThat(meterRegistry.get(UNKNOWN_JOB_ATTEMPT_REPORTS_METRIC).counter().count())
        .isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Should keep the replacement running and the stream session playable when a worker"
          + " reports an output mismatch for a job attempt that the server stopped")
  void
      shouldKeepReplacementRunningAndSessionPlayableWhenWorkerReportsOutputMismatchForStoppedJobAttempt()
          throws Exception {
    var started = startedEncode(new SimpleMeterRegistry());
    var job = started.job();
    var streamSessionId = fromProto(job.getStreamSessionId());
    started.registry().stopVariant(streamSessionId, job.getVariant().getVariantLabel());
    var replacement = job.toBuilder().setJobAttemptId(toProto(UUID.randomUUID())).build();
    assertThat(started.registry().dispatch(replacement)).isPresent();

    started
        .session()
        .onNext(
            EstablishWorkerSessionRequest.newBuilder()
                .setJobAttemptFailed(
                    JobAttemptFailed.newBuilder()
                        .setJobAttemptId(job.getJobAttemptId())
                        .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH))
                .build());

    assertThat(started.isRunning()).isTrue();
    assertThat(started.registry().terminalFailure(streamSessionId)).isEmpty();
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
      "Should report the worker's missing encoder until it reconnects with that encoder and drop the report when it disconnects")
  void
      shouldReportWorkersMissingEncoderUntilItReconnectsWithThatEncoderAndDropReportWhenItDisconnects()
          throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(meterRegistry)
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .build();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var workerId = UUID.randomUUID();
    var registration = registration(worker(workerId), UUID.randomUUID()).toBuilder();
    registration.getCapabilitiesBuilder().addVideoEncoders(LIBX264);
    workerSession(service, workerId, new TerminalErrorObserver())
        .onNext(EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
    var missingAv1 = missingEncoder(meterRegistry, workerId, "av1");

    registration
        .getCapabilitiesBuilder()
        .addVideoEncoders(softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1"));
    var reconnected = workerSession(service, workerId, new IgnoringResponseObserver());
    reconnected.onNext(
        EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
    var missingAv1AfterReconnect = missingEncoder(meterRegistry, workerId, "av1");
    reconnected.onError(Status.CANCELLED.asRuntimeException());

    assertThat(missingAv1).isEqualTo(1);
    assertThat(missingAv1AfterReconnect).isZero();
    assertThat(meterRegistry.find(MISSING_ENCODER_METRIC).gauges()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report the codec family as missing once a start report withdraws the worker's encoder for it")
  void shouldReportCodecFamilyAsMissingOnceStartReportWithdrawsWorkersEncoderForIt()
      throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var started = startedEncode(meterRegistry);
    var workerId = fromProto(started.worker().getWorkerId());
    var missingH264BeforeReport = missingEncoder(meterRegistry, workerId, "h264");

    started
        .session()
        .onNext(jobAttemptStarted(started.job(), ReportedEncoderBackend.DIFFERENT.backend()));

    assertThat(missingH264BeforeReport).isZero();
    assertThat(missingEncoder(meterRegistry, workerId, "h264")).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should report H.264 as missing when the worker's H.264 encoder is hardware without keyframe verification")
  void shouldReportH264AsMissingWhenWorkersH264EncoderIsHardwareWithoutKeyframeVerification()
      throws Exception {
    var missingH264 =
        missingH264AfterRegistering(
            hardwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc"));

    assertThat(missingH264).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should report no missing H.264 when the worker's H.264 encoder is keyframe-verified hardware")
  void shouldReportNoMissingH264WhenWorkersH264EncoderIsKeyframeVerifiedHardware()
      throws Exception {
    var missingH264 =
        missingH264AfterRegistering(
            keyframeVerifiedHardwareEncoder(
                VideoCodecFamily.VIDEO_CODEC_FAMILY_H264, "h264_nvenc"));

    assertThat(missingH264).isZero();
  }

  private static double missingH264AfterRegistering(WorkerVerifiedVideoEncoder h264Encoder)
      throws Exception {
    var meterRegistry = new SimpleMeterRegistry();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .eventPublisher(new CapturingEventPublisher())
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(meterRegistry)
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .build();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var workerId = UUID.randomUUID();
    var registration = registration(worker(workerId), UUID.randomUUID()).toBuilder();
    registration
        .getCapabilitiesBuilder()
        .addVideoEncoders(h264Encoder)
        .addVideoEncoders(softwareEncoder(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1, "libsvtav1"));
    workerSession(service, workerId, new IgnoringResponseObserver())
        .onNext(EstablishWorkerSessionRequest.newBuilder().setRegistration(registration).build());
    return missingEncoder(meterRegistry, workerId, "h264");
  }

  @Test
  @DisplayName(
      "Should report a worker's verified codec families when every playback slot of that worker is busy")
  void shouldReportWorkersVerifiedCodecFamiliesWhenEveryPlaybackSlotOfThatWorkerIsBusy()
      throws Exception {
    // The started encode holds the worker's only playback slot.
    var started = startedEncode(new SimpleMeterRegistry());
    var workerId = fromProto(started.worker().getWorkerId());

    var workers =
        started.registry().verifiedVideoCodecFamiliesByWorker(started.sourceNamespaceId());

    assertThat(started.isRunning()).isTrue();
    assertThat(workers).containsExactly(new WorkerVerifiedCodecFamilies(workerId, Set.of("h264")));
  }

  private static double missingEncoder(
      SimpleMeterRegistry meterRegistry, UUID workerId, String codecFamily) {
    return meterRegistry
        .get(MISSING_ENCODER_METRIC)
        .tag("worker", workerId.toString())
        .tag("codec_family", codecFamily)
        .gauge()
        .value();
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
        .sourceNamespaceId(sourceNamespaceId)
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
      UUID sourceNamespaceId,
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

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should publish a job attempt's progress only when its worker session runs it")
  void shouldPublishAJobAttemptsProgressOnlyWhenItsWorkerSessionRunsIt(boolean running)
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
    var connection = workerSession(service, workerId, new IgnoringResponseObserver());
    connection.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setRegistration(registration(worker(workerId), sourceNamespaceId))
            .build());
    var job = variantJob(sourceNamespaceId).toBuilder().setDecision(streamCopyDecision()).build();
    if (running) {
      assertThat(registry.dispatch(job)).isPresent();
    }

    connection.onNext(
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptProgress(
                JobAttemptProgress.newBuilder()
                    .setJobAttemptId(job.getJobAttemptId())
                    .setKeyframeSearchBytesRead(65_536))
            .build());

    var progressed =
        JobAttemptProgressedEvent.builder()
            .streamSessionId(fromProto(job.getStreamSessionId()))
            .jobAttemptId(fromProto(job.getJobAttemptId()))
            .variantLabel(job.getVariant().getVariantLabel())
            .build();
    assertThat(events.getEventsOfType(JobAttemptProgressedEvent.class))
        .isEqualTo(running ? List.of(progressed) : List.of());
  }

  // ADR 0037: each chunk of a media segment upload restarts the stall clock, so a large merged
  // segment that uploads slowly keeps its producer.
  @Test
  @DisplayName("Should publish the job attempt's progress for each chunk that its upload delivers")
  void shouldPublishTheJobAttemptsProgressForEachChunkThatItsUploadDelivers() throws Exception {
    var events = new CapturingEventPublisher();
    var registry =
        LiveWorkerConnectionRegistry.builder()
            .configuration(WorkerSessionServerConfiguration.builder().build())
            .meterRegistry(new SimpleMeterRegistry())
            .heldRequests(new HeldSegmentRequests(new SimpleMeterRegistry()))
            .eventPublisher(events)
            .build();
    var workerId = UUID.randomUUID();
    var sourceNamespaceId = UUID.randomUUID();
    var worker = worker(workerId);
    var workerSessionId =
        registry.register(
            workerId, registration(worker, sourceNamespaceId), new IgnoringResponseObserver());
    var job = variantJob(sourceNamespaceId);
    assertThat(registry.dispatch(job)).isPresent();
    var service = new WorkerSessionGrpcService(registry, new FakeSegmentStore());
    var upload = upload(service, workerId, new RecordingUploadResponseObserver());
    upload.onNext(
        UploadSegmentRequest.newBuilder()
            .setMetadata(
                metadataBuilder(workerSessionId, worker, job)
                    .setContentLengthBytes(1024L * 1024)
                    .build())
            .build());

    for (var chunk = 0; chunk < 3; chunk++) {
      upload.onNext(
          UploadSegmentRequest.newBuilder()
              .setData(ByteString.copyFrom(new byte[64 * 1024]))
              .build());
    }

    var progressed =
        JobAttemptProgressedEvent.builder()
            .streamSessionId(fromProto(job.getStreamSessionId()))
            .jobAttemptId(fromProto(job.getJobAttemptId()))
            .variantLabel(job.getVariant().getVariantLabel())
            .build();
    assertThat(events.getEventsOfType(JobAttemptProgressedEvent.class))
        .containsExactly(progressed, progressed, progressed);
    upload.onError(Status.CANCELLED.asRuntimeException());
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

  private static EstablishWorkerSessionRequest failureReport(Uuid jobAttemptId) {
    return EstablishWorkerSessionRequest.newBuilder()
        .setJobAttemptFailed(
            JobAttemptFailed.newBuilder()
                .setJobAttemptId(jobAttemptId)
                .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
        .build();
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
