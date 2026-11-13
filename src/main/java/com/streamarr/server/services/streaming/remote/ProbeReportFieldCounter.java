package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.ByteString;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.WireFormat;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the fields of an encoded probe report while the messages of its report call arrive, and
 * names the first rule that the bytes so far break: a limit that the server states, the decoded
 * size limit, or the protobuf wire format. It reads every message that the probe report holds, the
 * result's included, by the same rules. It builds no message, so the server decodes only a probe
 * report whose fields keep every limit (ADR 0062). It counts every occurrence of a field, in any
 * order of the fields, and a tag, a varint or a length may continue in the next message.
 *
 * <p>The decoded size limit is {@value #DECODED_BYTES_PER_BYTE_OF_ROOM} times the probe report's
 * room in the memory budget: its length, and at least {@value #SMALLEST_ROOM_BYTES} bytes. The
 * counter adds up the heap that the decoder would take for each message, list, number and text, and
 * ends the probe report as soon as that heap passes the limit, before the decoder allocates any of
 * it.
 */
final class ProbeReportFieldCounter {

  /** A rule that the bytes of a probe report break. */
  enum BrokenRule {
    RESULT_LENGTH("Worker probe report's result is longer than the result length limit"),
    KEYFRAMES("Worker probe report holds more keyframes than the keyframe limit"),
    MEASURED_KEYFRAME_INTERVALS(
        "Worker probe report holds more measured keyframe intervals than the limit"),
    MEASURED_STREAMS(
        "Worker probe report measures more streams than a result within the result length limit"
            + " can hold"),
    DECODED_SIZE(
        "Worker probe report would decode to more than "
            + DECODED_BYTES_PER_BYTE_OF_ROOM
            + " times its room in the memory budget"),
    WRONG_WIRE_TYPE("Worker probe report holds a field in a wire type that the field cannot have"),
    OVERLONG_VARINT("Worker probe report holds a varint longer than ten bytes"),
    INVALID_TAG("Worker probe report holds a tag of field number zero or of no wire type"),
    GROUP("Worker probe report holds a group"),
    FIELD_PAST_ITS_END("Worker probe report holds a field longer than the bytes that enclose it"),
    ENDS_INSIDE_A_FIELD("Worker probe report ends inside a field");

    private final String description;

    BrokenRule(String description) {
      this.description = description;
    }

    String description() {
      return description;
    }
  }

  // The decoder reads at most ten bytes of a varint: the 64 bits of its value.
  private static final int LONGEST_VARINT_BYTES = 10;
  // Every measured stream must also appear in the result, and a stream of the result takes at
  // least its tag and its length.
  private static final int FEWEST_BYTES_OF_A_STREAM_IN_THE_RESULT = 2;
  // A probe report decodes to at most this many times its room in the memory budget, so the probe
  // reports that the server reads at once decode to at most this many times the budget. A number
  // of one byte in a list takes up to twelve bytes once decoded, so a smaller multiple would refuse
  // valid probe reports.
  private static final long DECODED_BYTES_PER_BYTE_OF_ROOM = 16;
  // A probe report of a few hundred bytes decodes to many times its length, because each message
  // of its result decodes to an object.
  private static final long SMALLEST_ROOM_BYTES = 16L * 1024;
  // The most heap that protobuf-java's decoder takes, measured on the contract's messages: for a
  // message, for each field that its type declares, and for each repeated field's list. Each
  // bound is at least 1.4 times the heap measured on a 64-bit JVM.
  private static final long MESSAGE_BYTES = 64;
  private static final long FIELD_BYTES = 8;
  private static final long LIST_BYTES = 128;
  // A number in a list, with room for the list's growth.
  private static final long NUMBER_BYTES = 12;
  // A text or bytes field, beside two bytes for each byte of its length.
  private static final long TEXT_BYTES = 48;
  private static final Descriptor REPORT = ProbeAttemptReport.getDescriptor();
  private static final Descriptor MEASURED_STREAM = StreamMeasurementIntervals.getDescriptor();
  private static final FieldDescriptor RESULT =
      REPORT.findFieldByNumber(ProbeAttemptReport.RESULT_FIELD_NUMBER);

  private final long resultLengthLimitBytes;
  // The entries of these fields count against a limit, over every message that holds the field.
  private final Map<FieldDescriptor, Count> countedFields;
  private final long decodedSizeLimitBytes;
  private final Message report;
  private long resultBytes;
  private long decodedBytes;
  // Unset while the bytes keep every rule.
  private BrokenRule broken;

  ProbeReportFieldCounter(ProbeReportLimits limits, long reportLengthBytes) {
    resultLengthLimitBytes = limits.resultLengthLimitBytes();
    countedFields =
        Map.of(
            REPORT.findFieldByNumber(
                ProbeAttemptReport.KEYFRAME_PRESENTATION_TIME_DELTAS_FIELD_NUMBER),
            new Count(limits.keyframeLimit(), BrokenRule.KEYFRAMES),
            REPORT.findFieldByNumber(ProbeAttemptReport.KEYFRAME_DECODE_TIME_DELTAS_FIELD_NUMBER),
            new Count(limits.keyframeLimit(), BrokenRule.KEYFRAMES),
            REPORT.findFieldByNumber(ProbeAttemptReport.STREAM_MEASUREMENT_INTERVALS_FIELD_NUMBER),
            new Count(
                resultLengthLimitBytes / FEWEST_BYTES_OF_A_STREAM_IN_THE_RESULT,
                BrokenRule.MEASURED_STREAMS),
            MEASURED_STREAM.findFieldByNumber(
                StreamMeasurementIntervals.KEYFRAME_INTERVAL_BYTES_FIELD_NUMBER),
            new Count(
                limits.measuredKeyframeIntervalLimit(), BrokenRule.MEASURED_KEYFRAME_INTERVALS),
            MEASURED_STREAM.findFieldByNumber(
                StreamMeasurementIntervals.KEYFRAME_INTERVAL_PACKET_COUNTS_FIELD_NUMBER),
            new Count(
                limits.measuredKeyframeIntervalLimit(), BrokenRule.MEASURED_KEYFRAME_INTERVALS));
    decodedSizeLimitBytes = DECODED_BYTES_PER_BYTE_OF_ROOM * roomBytes(reportLengthBytes);
    report = new Message(REPORT, reportLengthBytes);
    addDecodedBytes(messageBytes(REPORT));
  }

  /** The room in the memory budget that a probe report of this length holds while it is read. */
  static long roomBytes(long reportLengthBytes) {
    return Math.max(reportLengthBytes, SMALLEST_ROOM_BYTES);
  }

  /** Reads the next bytes of the probe report. */
  Optional<BrokenRule> read(ByteString bytes) {
    var iterator = bytes.iterator();
    while (broken == null && iterator.hasNext()) {
      report.read(iterator.nextByte());
    }

    return Optional.ofNullable(broken);
  }

  /** Reads the end of the probe report, after its last byte. */
  Optional<BrokenRule> end() {
    if (!report.isBetweenFields()) {
      breakRule(BrokenRule.ENDS_INSIDE_A_FIELD);
    }

    return Optional.ofNullable(broken);
  }

  // A field that the contract does not declare may arrive in any wire type but a group's, which
  // the counter never reads as a field. Its encoding still counts, and the decoder discards it.
  private static boolean takes(FieldDescriptor field, int wireType) {
    if (field == null) {
      return true;
    }

    return wireType == field.getLiteType().getWireType()
        || (field.isPackable() && wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED);
  }

  private static long messageBytes(Descriptor type) {
    var bytes = MESSAGE_BYTES;
    for (var field : type.getFields()) {
      bytes += field.isRepeated() ? FIELD_BYTES + LIST_BYTES : FIELD_BYTES;
    }

    return bytes;
  }

  private void addDecodedBytes(long bytes) {
    decodedBytes += bytes;
    if (decodedBytes > decodedSizeLimitBytes) {
      breakRule(BrokenRule.DECODED_SIZE);
    }
  }

  private void countEntries(FieldDescriptor field, long entries) {
    var count = countedFields.get(field);
    if (count != null) {
      count.add(entries);
    }
  }

  private void addToResult(int length) {
    resultBytes += length;
    if (resultBytes > resultLengthLimitBytes) {
      breakRule(BrokenRule.RESULT_LENGTH);
    }
  }

  private void breakRule(BrokenRule rule) {
    if (broken == null) {
      broken = rule;
    }
  }

  /** Entries that count against a limit. */
  private final class Count {
    private final long limit;
    private final BrokenRule rule;
    private long entries;

    private Count(long limit, BrokenRule rule) {
      this.limit = limit;
      this.rule = rule;
    }

    private void add(long added) {
      entries += added;
      if (entries > limit) {
        breakRule(rule);
      }
    }
  }

  private enum Step {
    TAG,
    VARINT,
    LENGTH,
    SKIP,
    PACKED,
    MESSAGE
  }

  /**
   * Reads one message of the wire format, a byte at a time: the report, or a message that one of
   * its fields holds.
   */
  private final class Message {
    private final Descriptor type;
    // The bytes of the message that have not arrived.
    private long unread;
    private Step step = Step.TAG;
    private long varint;
    private int varintBytes;
    // Unset while the message reads a field that its type does not declare.
    private FieldDescriptor field;
    private long fieldUnread;
    // Set while the message reads a message that one of its fields holds.
    private Message inner;

    private Message(Descriptor type, long length) {
      this.type = type;
      this.unread = length;
    }

    private void read(byte b) {
      unread--;
      switch (step) {
        case TAG -> readTag(b);
        case VARINT -> readVarintValue(b);
        case LENGTH -> readLength(b);
        case SKIP -> readSkipped();
        case PACKED -> readPacked(b);
        case MESSAGE -> readInner(b);
      }
    }

    private boolean isBetweenFields() {
      return step == Step.TAG && varintBytes == 0;
    }

    private void readTag(byte b) {
      if (!addToVarint(b)) {
        return;
      }

      // The decoder reads a tag, as a length, from the low 32 bits of its varint.
      var tag = (int) takeVarint();
      var number = WireFormat.getTagFieldNumber(tag);
      if (number == 0) {
        breakRule(BrokenRule.INVALID_TAG);
        return;
      }

      field = type.findFieldByNumber(number);
      var wireType = WireFormat.getTagWireType(tag);
      switch (wireType) {
        case WireFormat.WIRETYPE_VARINT,
            WireFormat.WIRETYPE_FIXED64,
            WireFormat.WIRETYPE_LENGTH_DELIMITED,
            WireFormat.WIRETYPE_FIXED32 ->
            startField(wireType);
        case WireFormat.WIRETYPE_START_GROUP, WireFormat.WIRETYPE_END_GROUP ->
            breakRule(BrokenRule.GROUP);
        default -> breakRule(BrokenRule.INVALID_TAG);
      }
    }

    private void startField(int wireType) {
      if (!takes(field, wireType)) {
        breakRule(BrokenRule.WRONG_WIRE_TYPE);
        return;
      }

      switch (wireType) {
        case WireFormat.WIRETYPE_VARINT -> step = Step.VARINT;
        case WireFormat.WIRETYPE_FIXED64 -> skipNumber(Long.BYTES);
        case WireFormat.WIRETYPE_FIXED32 -> skipNumber(Integer.BYTES);
        default -> step = Step.LENGTH;
      }
    }

    private void readVarintValue(byte b) {
      if (!addToVarint(b)) {
        return;
      }

      takeVarint();
      step = Step.TAG;
      countNumbers(1);
    }

    private void readLength(byte b) {
      if (!addToVarint(b)) {
        return;
      }

      var length = (int) takeVarint();
      if (length < 0 || length > unread) {
        breakRule(BrokenRule.FIELD_PAST_ITS_END);
        return;
      }

      fieldUnread = length;
      step = startLengthDelimited(length);
      if (length == 0) {
        endField();
      }
    }

    private Step startLengthDelimited(int length) {
      if (field == null) {
        return Step.SKIP;
      }

      if (field.equals(RESULT)) {
        addToResult(length);
      }

      return switch (field.getJavaType()) {
        case MESSAGE -> {
          countEntries(field, 1);
          addDecodedBytes(messageBytes(field.getMessageType()));
          inner = new Message(field.getMessageType(), length);
          yield Step.MESSAGE;
        }
        case STRING, BYTE_STRING -> {
          addDecodedBytes(TEXT_BYTES + 2L * length);
          yield Step.SKIP;
        }
        // A packed run of varints: the contract declares no repeated field of a fixed width.
        default -> Step.PACKED;
      };
    }

    private void skipNumber(int length) {
      countNumbers(1);
      fieldUnread = length;
      step = Step.SKIP;
    }

    // Entries of a repeated field that holds numbers, or the value of a field that holds one.
    private void countNumbers(long numbers) {
      if (field != null && field.isRepeated()) {
        countEntries(field, numbers);
        addDecodedBytes(NUMBER_BYTES * numbers);
      }
    }

    private void readSkipped() {
      fieldUnread--;
      if (fieldUnread == 0) {
        endField();
      }
    }

    private void readPacked(byte b) {
      fieldUnread--;
      if (addToVarint(b)) {
        takeVarint();
        countNumbers(1);
      }

      if (fieldUnread == 0) {
        endField();
      }
    }

    private void readInner(byte b) {
      fieldUnread--;
      inner.read(b);
      if (fieldUnread == 0) {
        endField();
      }
    }

    private void endField() {
      var endsBetweenFields =
          switch (step) {
            case PACKED -> varintBytes == 0;
            case MESSAGE -> inner.isBetweenFields();
            default -> true;
          };
      step = Step.TAG;
      inner = null;
      if (!endsBetweenFields) {
        breakRule(BrokenRule.ENDS_INSIDE_A_FIELD);
      }
    }

    // Returns whether the byte ends the varint.
    private boolean addToVarint(byte b) {
      varint |= (long) (b & 0x7F) << (7 * varintBytes);
      varintBytes++;
      if ((b & 0x80) == 0) {
        return true;
      }

      if (varintBytes == LONGEST_VARINT_BYTES) {
        breakRule(BrokenRule.OVERLONG_VARINT);
      }

      return false;
    }

    private long takeVarint() {
      var value = varint;
      varint = 0;
      varintBytes = 0;
      return value;
    }
  }
}
