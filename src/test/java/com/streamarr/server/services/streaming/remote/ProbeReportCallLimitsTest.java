package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.intervals;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.invalidMedia;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.reportWithResultOfLength;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import io.grpc.Status;
import java.io.IOException;
import java.io.UncheckedIOException;
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
  private static final int LENGTH_LIMIT = (int) ProbeReportLimits.DEFAULT.lengthLimitBytes();
  private static final int DEFAULT_RESULT_LENGTH_LIMIT =
      (int) ProbeReportLimits.DEFAULT.resultLengthLimitBytes();
  // The most bytes of the probe report that one message of 128 KiB holds beside its framing.
  private static final int MESSAGE_BYTES = 131_000;

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

  @ParameterizedTest(name = "{0}")
  @EnumSource(FarBeyondItsLength.class)
  @DisplayName(
      "Should end the report call at the default limits before its last message when the probe"
          + " report would decode to far more heap than its length")
  void shouldEndReportCallAtDefaultLimitsBeforeLastMessageWhenReportWouldDecodeFarBeyondItsLength(
      FarBeyondItsLength shape) throws Exception {
    var worker = ReportingWorker.connectedTo(ProbeReportLimits.DEFAULT);
    var probe = worker.dispatch();
    var bytes = shape.report(probe.request());
    assertThat(bytes.size())
        .as("bytes after the last message that the server reads")
        .isGreaterThan(MESSAGE_BYTES * shape.lastMessageRead);
    var call = worker.openReportCall();
    call.sendMetadata(worker.metadata(probe.request()).setReportLengthBytes(bytes.size()));

    for (var message = 1; message < shape.lastMessageRead; message++) {
      call.sendData(message(bytes, message));
      assertThat(call.unreadDemand()).as("demand after message %d", message).isEqualTo(1);
    }

    call.sendData(message(bytes, shape.lastMessageRead));

    assertThat(call.refusal()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    assertThat(call.unreadDemand()).isZero();
    assertThatThrownBy(() -> probe.outcome().get(1, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseExactlyInstanceOf(ProbeContractViolationException.class);
    assertThat(worker.isConnected()).isTrue();
  }

  /**
   * Probe reports in which every entry of a repeated field takes one or two bytes, as a measurement
   * of the decoder found them: each such entry decodes to many times its length.
   */
  private enum FarBeyondItsLength {
    // The result is longer than the result length limit.
    RESULT_OF_EMPTY_STREAMS_AT_THE_LENGTH_LIMIT(1) {
      @Override
      ByteString report(ProbeRequest request) {
        return resultOfEmptyStreams((LENGTH_LIMIT - 16) / 2);
      }
    },
    // Each stream decodes to about 200 bytes. The streams take all of the result but the four
    // bytes of the media's tag and length.
    RESULT_OF_EMPTY_STREAMS_WITHIN_THE_RESULT_LENGTH_LIMIT(1) {
      @Override
      ByteString report(ProbeRequest request) {
        var report = resultOfEmptyStreams((DEFAULT_RESULT_LENGTH_LIMIT - 4) / 2);
        assertThat(report.size())
            .as("the result's tag and length, and a result within the result length limit")
            .isEqualTo(DEFAULT_RESULT_LENGTH_LIMIT + 4);
        return report;
      }
    },
    // The measured streams outnumber half the result length limit in the second message.
    EMPTY_MEASURED_STREAMS_AT_THE_LENGTH_LIMIT(2) {
      @Override
      ByteString report(ProbeRequest request) {
        return emptyEntries(
            ProbeAttemptReport.STREAM_MEASUREMENT_INTERVALS_FIELD_NUMBER, LENGTH_LIMIT / 2);
      }
    },
    // Each measured stream decodes to about 50 bytes.
    EMPTY_MEASURED_STREAMS_WITHIN_THE_MEASURED_STREAM_LIMIT(1) {
      @Override
      ByteString report(ProbeRequest request) {
        return emptyEntries(
                ProbeAttemptReport.STREAM_MEASUREMENT_INTERVALS_FIELD_NUMBER,
                DEFAULT_RESULT_LENGTH_LIMIT / 2)
            .concat(invalidMedia(request).build().toByteString());
      }
    },
    // The keyframes exceed the keyframe limit in the second message.
    ONE_BYTE_KEYFRAME_DELTAS_AT_THE_LENGTH_LIMIT(2) {
      @Override
      ByteString report(ProbeRequest request) {
        var deltas = new byte[LENGTH_LIMIT / 2 - 8];
        // A zigzag-encoded delta of 1.
        Arrays.fill(deltas, (byte) 2);
        return lengthDelimited(
                ProbeAttemptReport.KEYFRAME_PRESENTATION_TIME_DELTAS_FIELD_NUMBER,
                ByteString.copyFrom(deltas))
            .concat(
                lengthDelimited(
                    ProbeAttemptReport.KEYFRAME_DECODE_TIME_DELTAS_FIELD_NUMBER,
                    ByteString.copyFrom(deltas)));
      }
    };

    private final int lastMessageRead;

    FarBeyondItsLength(int lastMessageRead) {
      this.lastMessageRead = lastMessageRead;
    }

    abstract ByteString report(ProbeRequest request);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(DenseWithinTheLimits.class)
  @DisplayName(
      "Should accept a probe report at the default limits when its entries are as dense as the"
          + " limits allow")
  void shouldAcceptProbeReportAtDefaultLimitsWhenItsEntriesAreAsDenseAsLimitsAllow(
      DenseWithinTheLimits shape) throws Exception {
    var worker = ReportingWorker.connectedTo(ProbeReportLimits.DEFAULT);
    var probe = worker.dispatch();
    var report = shape.report(invalidMedia(probe.request())).build();

    var call = worker.report(probe.request(), report);

    assertThat(call.isAnswered()).isTrue();
    assertThat(probe.outcome().get(1, TimeUnit.SECONDS)).isEqualTo(report);
  }

  private enum DenseWithinTheLimits {
    // Two seconds between keyframes at 90 kHz, and eight measured streams.
    KEYFRAMES_AND_INTERVALS_AT_BOTH_LIMITS {
      @Override
      ProbeAttemptReport.Builder report(ProbeAttemptReport.Builder report) {
        var keyframes = ProbeReportLimits.DEFAULT.keyframeLimit();
        report
            .addAllKeyframePresentationTimeDeltas(Collections.nCopies(keyframes, 180_000L))
            .addAllKeyframeDecodeTimeDeltas(Collections.nCopies(keyframes, 180_000L));
        var streams = 8;
        var intervals = ProbeReportLimits.DEFAULT.measuredKeyframeIntervalLimit() / streams;
        for (var stream = 0; stream < streams; stream++) {
          report.addStreamMeasurementIntervals(
              StreamMeasurementIntervals.newBuilder()
                  .setStreamIndex(stream)
                  .addAllKeyframeIntervalBytes(Collections.nCopies(intervals, 1_000_000))
                  .addAllKeyframeIntervalPacketCounts(Collections.nCopies(intervals, 100)));
        }

        return report;
      }
    },
    ONE_BYTE_KEYFRAME_DELTAS_AT_THE_KEYFRAME_LIMIT {
      @Override
      ProbeAttemptReport.Builder report(ProbeAttemptReport.Builder report) {
        var deltas = Collections.nCopies(ProbeReportLimits.DEFAULT.keyframeLimit(), 1L);
        return report
            .addAllKeyframePresentationTimeDeltas(deltas)
            .addAllKeyframeDecodeTimeDeltas(deltas);
      }
    },
    ONE_BYTE_INTERVALS_AT_THE_INTERVAL_LIMIT {
      @Override
      ProbeAttemptReport.Builder report(ProbeAttemptReport.Builder report) {
        var intervals =
            Collections.nCopies(ProbeReportLimits.DEFAULT.measuredKeyframeIntervalLimit(), 1);
        return report.addStreamMeasurementIntervals(
            StreamMeasurementIntervals.newBuilder()
                .setStreamIndex(0)
                .addAllKeyframeIntervalBytes(intervals)
                .addAllKeyframeIntervalPacketCounts(intervals));
      }
    };

    /** Adds the dense entries to a probe report that holds a result. */
    abstract ProbeAttemptReport.Builder report(ProbeAttemptReport.Builder report);
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

  /** The message of the report call at this position, from one, of the most bytes that fit. */
  private static ByteString message(ByteString report, int position) {
    var start = MESSAGE_BYTES * (position - 1);
    return report.substring(start, Math.min(report.size(), start + MESSAGE_BYTES));
  }

  /** A probe report whose result's media holds this many streams, each a tag and a length of 0. */
  private static ByteString resultOfEmptyStreams(int streams) {
    var media =
        lengthDelimited(
            ProbeAttemptResult.MEDIA_FIELD_NUMBER,
            emptyEntries(ProbeMediaInfo.STREAMS_FIELD_NUMBER, streams));
    return lengthDelimited(ProbeAttemptReport.RESULT_FIELD_NUMBER, media);
  }

  /** This many entries of a repeated message field, each its one-byte tag and a length of 0. */
  private static ByteString emptyEntries(int fieldNumber, int entries) {
    var bytes = new byte[2 * entries];
    var tag = (byte) (fieldNumber << 3 | WireFormat.WIRETYPE_LENGTH_DELIMITED);
    for (var entry = 0; entry < entries; entry++) {
      bytes[2 * entry] = tag;
    }

    return ByteString.copyFrom(bytes);
  }

  private static ByteString lengthDelimited(int fieldNumber, ByteString content) {
    var out = ByteString.newOutput();
    var field = CodedOutputStream.newInstance(out);
    try {
      field.writeBytes(fieldNumber, content);
      field.flush();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }

    return out.toByteString();
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
