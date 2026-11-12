package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.intervals;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.invalidMedia;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.reportWithResultOfLength;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import io.grpc.Status;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Probe Report Call Limits Tests")
@Timeout(30)
class ProbeReportCallLimitsTest {

  private static final int RESULT_LENGTH_LIMIT = 64 * 1024;
  private static final ProbeReportLimits LIMITS =
      ProbeReportLimits.builder()
          .keyframeLimit(4)
          .measuredKeyframeIntervalLimit(6)
          .resultLengthLimitBytes(RESULT_LENGTH_LIMIT)
          .build();
  // Room for one report of 140,000 bytes, and for no second report of more than 60,000 bytes
  // beside it.
  private static final ProbeReportLimits TIGHT_MEMORY =
      ProbeReportLimits.builder()
          .lengthLimitBytes(150_000)
          .memoryBudgetBytes(200_000)
          .resultLengthLimitBytes(RESULT_LENGTH_LIMIT)
          .build();
  private static final int LENGTH_OF_HELD_REPORT = 140_000;
  private static final int LENGTH_OF_WAITING_REPORT = 100_000;

  @Test
  @DisplayName(
      "Should end the report call at the message that holds the length of a result longer than"
          + " the result length limit, before the result's bytes arrive")
  void shouldEndReportCallAtMessageThatHoldsLengthOfTooLongResultBeforeResultsBytesArrive()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var bytes = tooLongResult(probe.request());
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(bytes.size()));

    call.sendData(tagAndLengthOfResult(bytes));

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(call.unreadDemand()).as("The server asks for none of the result's bytes").isZero();
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseExactlyInstanceOf(ProbeContractViolationException.class);
    assertThat(worker.isConnected()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(CountedList.class)
  @DisplayName(
      "Should end the report call at the message after which a list holds more entries than its"
          + " limit, before its last message")
  void shouldEndReportCallAtMessageAfterWhichListHoldsMoreEntriesThanItsLimitBeforeLastMessage(
      CountedList list) throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var beyondTheLimit = list.beyondTheLimit().build().toByteString();
    var result = invalidMedia(probe.request()).build().toByteString();
    var call = worker.openReportCall();
    call.sendMetadata(
        worker
            .metadata(probe.request())
            .setReportLengthBytes((long) beyondTheLimit.size() + result.size()));

    call.sendData(beyondTheLimit);

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(call.unreadDemand()).as("The server asks for no message after the list").isZero();
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseExactlyInstanceOf(ProbeContractViolationException.class);
  }

  private enum CountedList {
    KEYFRAME_PRESENTATION_TIME_DELTAS {
      @Override
      ProbeAttemptReport.Builder beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addAllKeyframePresentationTimeDeltas(
                Collections.nCopies(LIMITS.keyframeLimit() + 1, 90_000L));
      }
    },
    KEYFRAME_DECODE_TIME_DELTAS {
      @Override
      ProbeAttemptReport.Builder beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addAllKeyframeDecodeTimeDeltas(
                Collections.nCopies(LIMITS.keyframeLimit() + 1, 90_000L));
      }
    },
    KEYFRAME_INTERVAL_BYTES {
      @Override
      ProbeAttemptReport.Builder beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addStreamMeasurementIntervals(intervals(0, LIMITS.measuredKeyframeIntervalLimit()))
            .addStreamMeasurementIntervals(
                StreamMeasurementIntervals.newBuilder()
                    .setStreamIndex(1)
                    .addKeyframeIntervalBytes(1_000));
      }
    },
    KEYFRAME_INTERVAL_PACKET_COUNTS {
      @Override
      ProbeAttemptReport.Builder beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addStreamMeasurementIntervals(intervals(0, LIMITS.measuredKeyframeIntervalLimit()))
            .addStreamMeasurementIntervals(
                StreamMeasurementIntervals.newBuilder()
                    .setStreamIndex(1)
                    .addKeyframeIntervalPacketCounts(1));
      }
    },
    MEASURED_STREAMS {
      @Override
      ProbeAttemptReport.Builder beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addAllStreamMeasurementIntervals(
                Collections.nCopies(
                    RESULT_LENGTH_LIMIT / 2 + 1, StreamMeasurementIntervals.getDefaultInstance()));
      }
    };

    /** Probe report fields that hold one entry of the list beyond its limit. */
    abstract ProbeAttemptReport.Builder beyondTheLimit();
  }

  @Test
  @DisplayName(
      "Should fail the probe as a contract violation, not as misconfigured, when a probe report of"
          + " another probe version breaks a stated limit")
  void shouldFailProbeAsContractViolationNotAsMisconfiguredWhenReportOfAnotherVersionBreaksLimit()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var report =
        CountedList.KEYFRAME_DECODE_TIME_DELTAS
            .beyondTheLimit()
            .mergeFrom(invalidMedia(probe.request()).build());
    report.getResultBuilder().setProbeVersion(ReportingWorker.PROBE_VERSION + 1);

    var call = worker.report(probe.request(), report.build());

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseExactlyInstanceOf(ProbeContractViolationException.class);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(BrokenEncoding.class)
  @DisplayName(
      "Should end the report call at the message whose bytes break the wire format, before its"
          + " last message")
  void shouldEndReportCallAtMessageWhoseBytesBreakWireFormatBeforeItsLastMessage(
      BrokenEncoding encoding) throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(64));

    call.sendData(encoding.bytes());

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(call.unreadDemand()).isZero();
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseExactlyInstanceOf(ProbeContractViolationException.class);
  }

  private enum BrokenEncoding {
    KEYFRAME_IN_A_VARINT_OF_ELEVEN_BYTES {
      @Override
      ByteString bytes() {
        var overlongVarint = new byte[11];
        Arrays.fill(overlongVarint, (byte) 0xff);
        overlongVarint[0] = 0x10;
        return ByteString.copyFrom(overlongVarint);
      }
    },
    KEYFRAME_AS_A_FIXED64_VALUE {
      @Override
      ByteString bytes() {
        var fixed64 = new byte[9];
        fixed64[0] = 0x11;
        return ByteString.copyFrom(fixed64);
      }
    };

    /** The first bytes of a probe report, which break the wire format. */
    abstract ByteString bytes();
  }

  @Test
  @DisplayName(
      "Should give the room of a report call to a waiting report call at once when the report"
          + " call breaks a limit before its last message")
  void shouldGiveRoomOfReportCallToWaitingReportCallAtOnceWhenItBreaksLimitBeforeItsLastMessage()
      throws Exception {
    var worker = ReportingWorker.connectedTo(TIGHT_MEMORY);
    var held = worker.dispatch();
    var holder = worker.openReportCall();
    holder.sendMetadata(
        worker.metadata(held.request()).setReportLengthBytes(LENGTH_OF_HELD_REPORT));
    var waitingProbe = worker.dispatch();
    var waiting = worker.openReportCall();
    waiting.sendMetadata(
        worker.metadata(waitingProbe.request()).setReportLengthBytes(LENGTH_OF_WAITING_REPORT));
    var demandWhileRoomIsHeld = waiting.unreadDemand();

    holder.sendData(tagAndLengthOfResult(tooLongResult(held.request())));

    assertThat(demandWhileRoomIsHeld).isZero();
    assertThat(holder.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(waiting.unreadDemand()).isEqualTo(1);
  }

  /** A probe report whose result is one byte longer than the result length limit. */
  private static ByteString tooLongResult(ProbeRequest request) {
    return reportWithResultOfLength(request, RESULT_LENGTH_LIMIT + 1).build().toByteString();
  }

  /** The result's tag and the bytes of its length, which open the probe report. */
  private static ByteString tagAndLengthOfResult(ByteString report) {
    var tagBytes = CodedOutputStream.computeTagSize(ProbeAttemptReport.RESULT_FIELD_NUMBER);
    return report.substring(
        0, tagBytes + CodedOutputStream.computeUInt32SizeNoTag(RESULT_LENGTH_LIMIT + 1));
  }
}
