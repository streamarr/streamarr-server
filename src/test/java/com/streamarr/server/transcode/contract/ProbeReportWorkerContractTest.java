package com.streamarr.server.transcode.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.Type;
import com.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import com.streamarr.transcode.v1.KeyframesUnavailableReason;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import com.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import com.streamarr.transcode.v1.Uuid;
import com.streamarr.transcode.v1.WorkerIdentity;
import com.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.MethodDescriptor.MethodType;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Report Worker Contract Tests")
class ProbeReportWorkerContractTest {

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
      "Should keep the field numbers and types that workers build against when publishing the"
          + " probe report call")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenPublishingTheProbeReportCall() {
    var request = ReportProbeAttemptRequest.getDescriptor();
    var metadata = ProbeAttemptReportMetadata.getDescriptor();
    var response = ReportProbeAttemptResponse.getDescriptor();

    assertThat(fields(request, "metadata", "data"))
        .containsExactly(
            tuple("metadata", 1, Type.MESSAGE, true, false),
            tuple("data", 2, Type.BYTES, true, false));
    assertThat(request.getFields())
        .allSatisfy(field -> assertThat(field.getContainingOneof().getName()).isEqualTo("frame"));
    assertThat(
            fields(
                metadata, "worker_session_id", "worker", "probe_attempt_id", "report_length_bytes"))
        .containsExactly(
            tuple("worker_session_id", 1, Type.MESSAGE, true, false),
            tuple("worker", 2, Type.MESSAGE, true, false),
            tuple("probe_attempt_id", 3, Type.MESSAGE, true, false),
            tuple("report_length_bytes", 4, Type.UINT64, false, false));
    assertThat(fields(response, "probe_attempt_id", "accepted_length_bytes"))
        .containsExactly(
            tuple("probe_attempt_id", 1, Type.MESSAGE, true, false),
            tuple("accepted_length_bytes", 2, Type.UINT64, false, false));
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
            tuple("result", 1, Type.MESSAGE, true, false),
            tuple("keyframe_presentation_time_deltas", 2, Type.SINT64, false, true),
            tuple("keyframe_decode_time_deltas", 3, Type.SINT64, false, true),
            tuple("stream_measurement_intervals", 4, Type.MESSAGE, false, true));
    assertThat(messageTypes(report, "result", "stream_measurement_intervals"))
        .containsExactly(ProbeAttemptResult.getDescriptor().getFullName(), intervals.getFullName());
    assertThat(
            fields(
                intervals,
                "stream_index",
                "keyframe_interval_bytes",
                "keyframe_interval_packet_counts"))
        .containsExactly(
            tuple("stream_index", 1, Type.UINT32, false, false),
            tuple("keyframe_interval_bytes", 2, Type.UINT32, false, true),
            tuple("keyframe_interval_packet_counts", 3, Type.UINT32, false, true));
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
                "measured_keyframe_interval_limit",
                "probe_result_length_limit_bytes"))
        .containsExactly(
            tuple("probe_report_limit", 3, Type.UINT32, false, false),
            tuple("probe_report_length_limit_bytes", 4, Type.UINT64, false, false),
            tuple("keyframe_limit", 5, Type.UINT32, false, false),
            tuple("measured_keyframe_interval_limit", 6, Type.UINT32, false, false),
            tuple("probe_result_length_limit_bytes", 7, Type.UINT64, false, false));
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

  @Test
  @DisplayName(
      "Should keep the field numbers and types that workers build against when a probe attempt"
          + " ends without a report")
  void shouldKeepTheFieldNumbersAndTypesThatWorkersBuildAgainstWhenProbeAttemptEndsWithoutReport() {
    var event =
        EstablishWorkerSessionRequest.getDescriptor()
            .findFieldByName("probe_attempt_ended_without_report");

    assertThat(event).isNotNull();
    assertThat(fields(event.getContainingType(), "probe_attempt_ended_without_report"))
        .containsExactly(
            tuple("probe_attempt_ended_without_report", 12, Type.MESSAGE, true, false));
    assertThat(event.getContainingOneof().getName()).isEqualTo("event");
    var ended = event.getMessageType();
    assertThat(ended.getName()).isEqualTo("ProbeAttemptEndedWithoutReport");
    assertThat(ended.getFields())
        .extracting(FieldDescriptor::getName)
        .containsExactly("probe_attempt_id");
    assertThat(fields(ended, "probe_attempt_id"))
        .containsExactly(tuple("probe_attempt_id", 1, Type.MESSAGE, true, false));
    assertThat(messageTypes(ended, "probe_attempt_id"))
        .containsExactly(Uuid.getDescriptor().getFullName());
  }

  // Each tuple holds a field's name, number, type, presence and repeatedness. A scalar that turns
  // repeated keeps the first four, but its value travels packed on the wire.
  private static List<Tuple> fields(Descriptor message, String... names) {
    return Arrays.stream(names)
        .map(message::findFieldByName)
        .map(
            field ->
                tuple(
                    field.getName(),
                    field.getNumber(),
                    field.getType(),
                    field.hasPresence(),
                    field.isRepeated()))
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
