package com.streamarr.server.transcode.contract;

import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import com.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import com.streamarr.transcode.v1.KeyframePositionSource;
import com.streamarr.transcode.v1.KeyframePositions;
import com.streamarr.transcode.v1.KeyframesUnavailable;
import com.streamarr.transcode.v1.KeyframesUnavailableReason;
import com.streamarr.transcode.v1.MeasuredStream;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest.FrameCase;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import com.streamarr.transcode.v1.StreamMeasurementSource;
import com.streamarr.transcode.v1.StreamMeasurements;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.MethodDescriptor.MethodType;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Report Worker Contract Tests")
class ProbeReportWorkerContractTest {

  private static final int VIDEO_STREAM_INDEX = 0;
  private static final int AUDIO_STREAM_INDEX = 1;

  @Test
  @DisplayName(
      "Should send a probe report in a client-streaming call of its own when validating the"
          + " protocol")
  void shouldSendAProbeReportInAClientStreamingCallOfItsOwnWhenValidatingTheProtocol() {
    var declared =
        ReportProbeAttemptRequest.getDescriptor()
            .getFile()
            .findServiceByName("TranscodeWorkerService")
            .findMethodByName("ReportProbeAttempt");

    assertThat(TranscodeWorkerServiceGrpc.getReportProbeAttemptMethod().getType())
        .isEqualTo(MethodType.CLIENT_STREAMING);
    assertThat(declared.getInputType().getFullName())
        .isEqualTo(ReportProbeAttemptRequest.getDescriptor().getFullName());
    assertThat(declared.getOutputType().getFullName())
        .isEqualTo(ReportProbeAttemptResponse.getDescriptor().getFullName());
  }

  @Test
  @DisplayName(
      "Should keep the result, the keyframe positions and the measurement when a probe report is"
          + " encoded and decoded")
  void shouldKeepTheResultTheKeyframePositionsAndTheMeasurementWhenProbeReportIsEncodedAndDecoded()
      throws Exception {
    var report = measuredProbeReport(toProto(UUID.randomUUID()));

    var decoded = ProbeAttemptReport.parseFrom(report.toByteArray());

    assertThat(decoded.getResult()).isEqualTo(report.getResult());
    assertThat(decoded.getKeyframePresentationTimeDeltasList())
        .containsExactly(3_750L, 180_000L, 176_250L);
    assertThat(decoded.getKeyframeDecodeTimeDeltasList())
        .containsExactly(-3_750L, 180_000L, 183_750L);
    assertThat(decoded.getStreamMeasurementIntervalsList())
        .containsExactly(
            intervals(
                VIDEO_STREAM_INDEX, List.of(1_500_000, 1_450_000, 900_000), List.of(48, 48, 30)),
            intervals(AUDIO_STREAM_INDEX, List.of(32_000, 32_000, 20_000), List.of(94, 94, 59)));
  }

  @Test
  @DisplayName(
      "Should carry the reason without keyframe positions or a measurement when the chosen video"
          + " stream holds too many keyframes")
  void shouldCarryTheReasonWithoutPositionsOrMeasurementWhenStreamHoldsTooManyKeyframes()
      throws Exception {
    var unavailable =
        KeyframesUnavailable.newBuilder()
            .setReason(KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_TOO_MANY_KEYFRAMES);
    var report =
        ProbeAttemptReport.newBuilder()
            .setResult(
                ProbeAttemptResult.newBuilder()
                    .setProbeAttemptId(toProto(UUID.randomUUID()))
                    .setProbeVersion(8)
                    .setMedia(
                        ProbeMediaInfo.newBuilder()
                            .setKeyframes(ProbeKeyframes.newBuilder().setUnavailable(unavailable))))
            .build();

    var decoded = ProbeAttemptReport.parseFrom(report.toByteArray());

    assertThat(decoded.getResult().getMedia().getKeyframes().getUnavailable().getReason())
        .isEqualTo(KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_TOO_MANY_KEYFRAMES);
    assertThat(decoded.getKeyframePresentationTimeDeltasList()).isEmpty();
    assertThat(decoded.getKeyframeDecodeTimeDeltasList()).isEmpty();
    assertThat(decoded.getStreamMeasurementIntervalsList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should name the worker session, the worker, the probe attempt and the report length when"
          + " the first request carries the metadata")
  void shouldNameTheSessionTheWorkerTheAttemptAndTheReportLengthWhenFirstRequestCarriesMetadata()
      throws Exception {
    var metadata =
        ProbeAttemptReportMetadata.newBuilder()
            .setWorkerSessionId(toProto(UUID.randomUUID()))
            .setWorker(workerIdentity())
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setReportLengthBytes(472_587)
            .build();
    var request = ReportProbeAttemptRequest.newBuilder().setMetadata(metadata).build();

    var decoded = ReportProbeAttemptRequest.parseFrom(request.toByteArray());

    assertThat(decoded.getFrameCase()).isEqualTo(FrameCase.METADATA);
    assertThat(decoded.getMetadata()).isEqualTo(metadata);
  }

  @Test
  @DisplayName(
      "Should rebuild the probe report from the data of the later requests when they carry its"
          + " encoded bytes in order")
  void shouldRebuildTheProbeReportFromTheDataOfLaterRequestsWhenTheyCarryItsEncodedBytesInOrder()
      throws Exception {
    var report = measuredProbeReport(toProto(UUID.randomUUID()));
    var encoded = report.toByteString();
    var middle = encoded.size() / 2;
    var firstChunk =
        ReportProbeAttemptRequest.newBuilder().setData(encoded.substring(0, middle)).build();
    var secondChunk =
        ReportProbeAttemptRequest.newBuilder().setData(encoded.substring(middle)).build();

    var first = ReportProbeAttemptRequest.parseFrom(firstChunk.toByteArray());
    var second = ReportProbeAttemptRequest.parseFrom(secondChunk.toByteArray());

    assertThat(first.getFrameCase()).isEqualTo(FrameCase.DATA);
    assertThat(second.getFrameCase()).isEqualTo(FrameCase.DATA);
    assertThat(ProbeAttemptReport.parseFrom(first.getData().concat(second.getData())))
        .isEqualTo(report);
  }

  @Test
  @DisplayName(
      "Should name the probe attempt and the accepted length when the server answers a probe"
          + " report")
  void shouldNameTheProbeAttemptAndTheAcceptedLengthWhenServerAnswersAProbeReport()
      throws Exception {
    var attemptId = toProto(UUID.randomUUID());
    var response =
        ReportProbeAttemptResponse.newBuilder()
            .setProbeAttemptId(attemptId)
            .setAcceptedLengthBytes(472_587)
            .build();

    var decoded = ReportProbeAttemptResponse.parseFrom(response.toByteArray());

    assertThat(decoded.getProbeAttemptId()).isEqualTo(attemptId);
    assertThat(decoded.getAcceptedLengthBytes()).isEqualTo(472_587);
  }

  @Test
  @DisplayName("Should state the probe report limits when the server accepts a worker session")
  void shouldStateTheProbeReportLimitsWhenServerAcceptsAWorkerSession() throws Exception {
    var accepted =
        EstablishWorkerSessionResponse.newBuilder()
            .setSessionAccepted(
                WorkerSessionAccepted.newBuilder()
                    .setWorkerSessionId(toProto(UUID.randomUUID()))
                    .setSegmentUploadLimit(8)
                    .setProbeReportLimit(4)
                    .setProbeReportLengthLimitBytes(16_777_216)
                    .setKeyframeLimit(131_072)
                    .setMeasuredKeyframeIntervalLimit(1_048_576))
            .build();

    var decoded =
        EstablishWorkerSessionResponse.parseFrom(accepted.toByteArray()).getSessionAccepted();

    assertThat(decoded.getSegmentUploadLimit()).isEqualTo(8);
    assertThat(decoded.getProbeReportLimit()).isEqualTo(4);
    assertThat(decoded.getProbeReportLengthLimitBytes()).isEqualTo(16_777_216);
    assertThat(decoded.getKeyframeLimit()).isEqualTo(131_072);
    assertThat(decoded.getMeasuredKeyframeIntervalLimit()).isEqualTo(1_048_576);
  }

  @Test
  @DisplayName("Should read each probe report limit as 0 when a server leaves it out")
  void shouldReadEachProbeReportLimitAsZeroWhenServerLeavesItOut() throws Exception {
    var accepted =
        WorkerSessionAccepted.newBuilder()
            .setWorkerSessionId(toProto(UUID.randomUUID()))
            .setSegmentUploadLimit(8)
            .build();

    var decoded = WorkerSessionAccepted.parseFrom(accepted.toByteArray());

    assertThat(decoded.getProbeReportLimit()).isZero();
    assertThat(decoded.getProbeReportLengthLimitBytes()).isZero();
    assertThat(decoded.getKeyframeLimit()).isZero();
    assertThat(decoded.getMeasuredKeyframeIntervalLimit()).isZero();
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the"
          + " probe report call")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheProbeReportCall() {
    var request = ReportProbeAttemptRequest.getDescriptor();
    var metadata = ProbeAttemptReportMetadata.getDescriptor();
    var response = ReportProbeAttemptResponse.getDescriptor();

    assertThat(fields(request, "metadata", "data"))
        .containsExactly(
            tuple("metadata", 1, Type.MESSAGE, true), tuple("data", 2, Type.BYTES, true));
    assertThat(request.getFields())
        .allSatisfy(field -> assertThat(field.getContainingOneof().getName()).isEqualTo("frame"));
    assertThat(
            fields(
                metadata, "worker_session_id", "worker", "probe_attempt_id", "report_length_bytes"))
        .containsExactly(
            tuple("worker_session_id", 1, Type.MESSAGE, true),
            tuple("worker", 2, Type.MESSAGE, true),
            tuple("probe_attempt_id", 3, Type.MESSAGE, true),
            tuple("report_length_bytes", 4, Type.UINT64, false));
    assertThat(fields(response, "probe_attempt_id", "accepted_length_bytes"))
        .containsExactly(
            tuple("probe_attempt_id", 1, Type.MESSAGE, true),
            tuple("accepted_length_bytes", 2, Type.UINT64, false));
    assertThat(messageTypes(request, "metadata")).containsExactly(metadata.getFullName());
    assertThat(messageTypes(metadata, "worker_session_id", "worker", "probe_attempt_id"))
        .containsExactly(
            Uuid.getDescriptor().getFullName(),
            WorkerIdentity.getDescriptor().getFullName(),
            Uuid.getDescriptor().getFullName());
    assertThat(messageTypes(response, "probe_attempt_id"))
        .containsExactly(Uuid.getDescriptor().getFullName());
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the"
          + " probe report")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheProbeReport() {
    var report = ProbeAttemptReport.getDescriptor();
    var intervals = StreamMeasurementIntervals.getDescriptor();

    assertThat(
            fields(
                report,
                "result",
                "keyframe_presentation_time_deltas",
                "keyframe_decode_time_deltas",
                "stream_measurement_intervals"))
        .containsExactly(
            tuple("result", 1, Type.MESSAGE, true),
            tuple("keyframe_presentation_time_deltas", 2, Type.SINT64, false),
            tuple("keyframe_decode_time_deltas", 3, Type.SINT64, false),
            tuple("stream_measurement_intervals", 4, Type.MESSAGE, false));
    assertThat(repeatedFields(report))
        .containsExactly(
            "keyframe_presentation_time_deltas",
            "keyframe_decode_time_deltas",
            "stream_measurement_intervals");
    assertThat(messageTypes(report, "result", "stream_measurement_intervals"))
        .containsExactly(ProbeAttemptResult.getDescriptor().getFullName(), intervals.getFullName());
    assertThat(
            fields(
                intervals,
                "stream_index",
                "keyframe_interval_bytes",
                "keyframe_interval_packet_counts"))
        .containsExactly(
            tuple("stream_index", 1, Type.UINT32, false),
            tuple("keyframe_interval_bytes", 2, Type.UINT32, false),
            tuple("keyframe_interval_packet_counts", 3, Type.UINT32, false));
    assertThat(repeatedFields(intervals))
        .containsExactly("keyframe_interval_bytes", "keyframe_interval_packet_counts");
  }

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when publishing the"
          + " probe report limits")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingProbeReportLimits() {
    assertThat(
            fields(
                WorkerSessionAccepted.getDescriptor(),
                "probe_report_limit",
                "probe_report_length_limit_bytes",
                "keyframe_limit",
                "measured_keyframe_interval_limit"))
        .containsExactly(
            tuple("probe_report_limit", 3, Type.UINT32, false),
            tuple("probe_report_length_limit_bytes", 4, Type.UINT64, false),
            tuple("keyframe_limit", 5, Type.UINT32, false),
            tuple("measured_keyframe_interval_limit", 6, Type.UINT32, false));
  }

  @Test
  @DisplayName(
      "Should keep the unavailable reason values that workers build against when publishing the"
          + " keyframe limit")
  void shouldKeepTheUnavailableReasonValuesThatWorkersBuildAgainstWhenPublishingKeyframeLimit() {
    assertThat(values(KeyframesUnavailableReason.getDescriptor()))
        .containsExactly(
            tuple("KEYFRAMES_UNAVAILABLE_REASON_UNSPECIFIED", 0),
            tuple("KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP", 1),
            tuple("KEYFRAMES_UNAVAILABLE_REASON_NO_KEYFRAMES", 2),
            tuple("KEYFRAMES_UNAVAILABLE_REASON_TOO_MANY_KEYFRAMES", 3));
  }

  // Three keyframes of a 90 kHz video stream, in decode order. B-frame reordering presents the
  // first keyframe 3,750 ticks after the container start and decodes it 3,750 ticks before. The
  // measurement covers the video stream and one audio stream.
  private static ProbeAttemptReport measuredProbeReport(Uuid probeAttemptId) {
    var media =
        ProbeMediaInfo.newBuilder()
            .addStreams(
                ProbeStreamInfo.newBuilder().setIndex(VIDEO_STREAM_INDEX).setCodecType("video"))
            .addStreams(
                ProbeStreamInfo.newBuilder().setIndex(AUDIO_STREAM_INDEX).setCodecType("audio"))
            .setChosenVideoStreamIndex(VIDEO_STREAM_INDEX)
            .setKeyframes(
                ProbeKeyframes.newBuilder()
                    .setPositions(
                        KeyframePositions.newBuilder()
                            .setStreamIndex(VIDEO_STREAM_INDEX)
                            .setTimeBaseNumerator(1)
                            .setTimeBaseDenominator(90_000)
                            .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
                            .setKeyframeCount(3)))
            .setStreamMeasurements(
                StreamMeasurements.newBuilder()
                    .setSource(
                        StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_KEYFRAME_PACKET_SCAN)
                    .addStreams(MeasuredStream.newBuilder().setStreamIndex(VIDEO_STREAM_INDEX))
                    .addStreams(MeasuredStream.newBuilder().setStreamIndex(AUDIO_STREAM_INDEX)));
    return ProbeAttemptReport.newBuilder()
        .setResult(
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(probeAttemptId)
                .setProbeVersion(8)
                .setMedia(media))
        .addAllKeyframePresentationTimeDeltas(List.of(3_750L, 180_000L, 176_250L))
        .addAllKeyframeDecodeTimeDeltas(List.of(-3_750L, 180_000L, 183_750L))
        .addStreamMeasurementIntervals(
            intervals(
                VIDEO_STREAM_INDEX, List.of(1_500_000, 1_450_000, 900_000), List.of(48, 48, 30)))
        .addStreamMeasurementIntervals(
            intervals(AUDIO_STREAM_INDEX, List.of(32_000, 32_000, 20_000), List.of(94, 94, 59)))
        .build();
  }

  private static StreamMeasurementIntervals intervals(
      int streamIndex, List<Integer> bytes, List<Integer> packetCounts) {
    return StreamMeasurementIntervals.newBuilder()
        .setStreamIndex(streamIndex)
        .addAllKeyframeIntervalBytes(bytes)
        .addAllKeyframeIntervalPacketCounts(packetCounts)
        .build();
  }

  private static WorkerIdentity workerIdentity() {
    return WorkerIdentity.newBuilder()
        .setWorkerId(toProto(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")))
        .setBootId(toProto(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")))
        .build();
  }

  private static List<Tuple> fields(Descriptor message, String... names) {
    return Arrays.stream(names)
        .map(message::findFieldByName)
        .map(
            field ->
                tuple(field.getName(), field.getNumber(), field.getType(), field.hasPresence()))
        .toList();
  }

  private static List<String> repeatedFields(Descriptor message) {
    return message.getFields().stream()
        .filter(FieldDescriptor::isRepeated)
        .map(FieldDescriptor::getName)
        .toList();
  }

  private static List<String> messageTypes(Descriptor message, String... names) {
    return Arrays.stream(names)
        .map(message::findFieldByName)
        .map(field -> field.getMessageType().getFullName())
        .toList();
  }

  private static List<Tuple> values(EnumDescriptor type) {
    return type.getValues().stream()
        .map(value -> tuple(value.getName(), value.getNumber()))
        .toList();
  }
}
