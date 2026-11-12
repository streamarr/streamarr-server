package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.reportWithResultOfLength;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.ProbeRequest;
import io.grpc.Status;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("UnitTest")
@DisplayName("Probe Report Call Limits Tests")
@Timeout(30)
class ProbeReportCallLimitsTest {

  private static final int RESULT_LENGTH_LIMIT = 1_000;
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

  @Test
  @DisplayName(
      "Should end the report call at the message whose bytes break the wire format, before its"
          + " last message")
  void shouldEndReportCallAtMessageWhoseBytesBreakWireFormatBeforeItsLastMessage()
      throws Exception {
    var worker = ReportingWorker.connectedTo(LIMITS);
    var probe = worker.dispatch();
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(64));
    // A keyframe whose varint goes on past ten bytes.
    var overlongVarint = new byte[11];
    Arrays.fill(overlongVarint, (byte) 0xff);
    overlongVarint[0] = 0x10;

    call.sendData(ByteString.copyFrom(overlongVarint));

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(call.unreadDemand()).isZero();
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseExactlyInstanceOf(ProbeContractViolationException.class);
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

  /** The result's tag and the two bytes of its length, which open the probe report. */
  private static ByteString tagAndLengthOfResult(ByteString report) {
    return report.substring(0, 3);
  }
}
