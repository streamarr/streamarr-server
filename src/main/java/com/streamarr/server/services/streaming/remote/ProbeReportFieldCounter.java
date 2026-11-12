package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.ByteString;
import com.google.protobuf.WireFormat;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;

/**
 * Reads the fields of an encoded probe report while the messages of its report call arrive, and
 * names the first rule that the bytes so far break: a limit that the server states, or the protobuf
 * wire format. It builds no message, so the server decodes only a probe report whose fields keep
 * every limit (ADR 0062). It counts every occurrence of a field, in any order of the fields, and a
 * tag, a varint or a length may continue in the next message.
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
  private static final Field STREAM_INDEX = new Skipped(Set.of(WireFormat.WIRETYPE_VARINT));
  // A field that the contract does not declare may arrive in any wire type but a group's. Its
  // encoding still counts, and the decoder discards it.
  private static final Field UNDECLARED =
      new Skipped(
          Set.of(
              WireFormat.WIRETYPE_VARINT,
              WireFormat.WIRETYPE_FIXED64,
              WireFormat.WIRETYPE_LENGTH_DELIMITED,
              WireFormat.WIRETYPE_FIXED32));

  private final long resultLengthLimitBytes;
  private final CountedList presentationTimeDeltas;
  private final CountedList decodeTimeDeltas;
  private final MeasuredStream measuredStreams;
  private final CountedList intervalBytes;
  private final CountedList intervalPacketCounts;
  private final Message report;
  private long resultBytes;
  // Unset while the bytes keep every rule.
  private BrokenRule broken;

  ProbeReportFieldCounter(ProbeReportLimits limits, long reportLengthBytes) {
    resultLengthLimitBytes = limits.resultLengthLimitBytes();
    presentationTimeDeltas = countedList(limits.keyframeLimit(), BrokenRule.KEYFRAMES);
    decodeTimeDeltas = countedList(limits.keyframeLimit(), BrokenRule.KEYFRAMES);
    measuredStreams =
        new MeasuredStream(
            new Count(
                resultLengthLimitBytes / FEWEST_BYTES_OF_A_STREAM_IN_THE_RESULT,
                BrokenRule.MEASURED_STREAMS));
    intervalBytes =
        countedList(limits.measuredKeyframeIntervalLimit(), BrokenRule.MEASURED_KEYFRAME_INTERVALS);
    intervalPacketCounts =
        countedList(limits.measuredKeyframeIntervalLimit(), BrokenRule.MEASURED_KEYFRAME_INTERVALS);
    report = new Message(reportLengthBytes, this::reportField);
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

  private CountedList countedList(long limit, BrokenRule rule) {
    return new CountedList(new Count(limit, rule));
  }

  private Field reportField(int number) {
    return switch (number) {
      case ProbeAttemptReport.RESULT_FIELD_NUMBER -> new Result();
      case ProbeAttemptReport.KEYFRAME_PRESENTATION_TIME_DELTAS_FIELD_NUMBER ->
          presentationTimeDeltas;
      case ProbeAttemptReport.KEYFRAME_DECODE_TIME_DELTAS_FIELD_NUMBER -> decodeTimeDeltas;
      case ProbeAttemptReport.STREAM_MEASUREMENT_INTERVALS_FIELD_NUMBER -> measuredStreams;
      default -> UNDECLARED;
    };
  }

  private Field measuredStreamField(int number) {
    return switch (number) {
      case StreamMeasurementIntervals.STREAM_INDEX_FIELD_NUMBER -> STREAM_INDEX;
      case StreamMeasurementIntervals.KEYFRAME_INTERVAL_BYTES_FIELD_NUMBER -> intervalBytes;
      case StreamMeasurementIntervals.KEYFRAME_INTERVAL_PACKET_COUNTS_FIELD_NUMBER ->
          intervalPacketCounts;
      default -> UNDECLARED;
    };
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

  /** What a field number names in the message that holds the field. */
  private sealed interface Field {

    /** Whether the field can arrive in the wire type. */
    boolean takes(int wireType);
  }

  /** The probe report's result, whose length counts and whose bytes the counter skips. */
  private record Result() implements Field {

    @Override
    public boolean takes(int wireType) {
      return wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }
  }

  /**
   * The probe report's measured streams, which count against a limit, and whose fields the counter
   * reads.
   */
  private record MeasuredStream(Count entries) implements Field {

    @Override
    public boolean takes(int wireType) {
      return wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }
  }

  /** A repeated scalar field whose entries count against a limit, packed or not. */
  private record CountedList(Count entries) implements Field {

    @Override
    public boolean takes(int wireType) {
      return wireType == WireFormat.WIRETYPE_VARINT
          || wireType == WireFormat.WIRETYPE_LENGTH_DELIMITED;
    }
  }

  /** A field whose bytes the counter skips, in the wire types that the field can have. */
  private record Skipped(Set<Integer> wireTypes) implements Field {

    @Override
    public boolean takes(int wireType) {
      return wireTypes.contains(wireType);
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

    private void add() {
      entries++;
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
    MEASURED_STREAM
  }

  /** Reads one message of the wire format, a byte at a time: the report, or a measured stream. */
  private final class Message {
    private final IntFunction<Field> fields;
    // The bytes of the message that have not arrived.
    private long unread;
    private Step step = Step.TAG;
    private long varint;
    private int varintBytes;
    private Field field;
    private long fieldUnread;
    // Set while the message reads a packed run of this count's entries.
    private Count packed;
    // Set while the message reads one of its measured streams.
    private Message measuredStream;

    private Message(long length, IntFunction<Field> fields) {
      this.unread = length;
      this.fields = fields;
    }

    private void read(byte b) {
      unread--;
      switch (step) {
        case TAG -> readTag(b);
        case VARINT -> readVarintValue(b);
        case LENGTH -> readLength(b);
        case SKIP -> readSkipped();
        case PACKED -> readPacked(b);
        case MEASURED_STREAM -> readMeasuredStream(b);
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

      field = fields.apply(number);
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
      if (!field.takes(wireType)) {
        breakRule(BrokenRule.WRONG_WIRE_TYPE);
        return;
      }

      switch (wireType) {
        case WireFormat.WIRETYPE_VARINT -> step = Step.VARINT;
        case WireFormat.WIRETYPE_FIXED64 -> skip(Long.BYTES);
        case WireFormat.WIRETYPE_FIXED32 -> skip(Integer.BYTES);
        default -> step = Step.LENGTH;
      }
    }

    private void readVarintValue(byte b) {
      if (!addToVarint(b)) {
        return;
      }

      takeVarint();
      step = Step.TAG;
      if (field instanceof CountedList(var entries)) {
        entries.add();
      }
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
      step =
          switch (field) {
            case Result _ -> {
              addToResult(length);
              yield Step.SKIP;
            }
            case CountedList(var entries) -> {
              packed = entries;
              yield Step.PACKED;
            }
            case MeasuredStream(var entries) -> {
              entries.add();
              measuredStream =
                  new Message(length, ProbeReportFieldCounter.this::measuredStreamField);
              yield Step.MEASURED_STREAM;
            }
            case Skipped _ -> Step.SKIP;
          };
      if (length == 0) {
        endField();
      }
    }

    private void skip(int length) {
      fieldUnread = length;
      step = Step.SKIP;
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
        packed.add();
      }

      if (fieldUnread == 0) {
        endField();
      }
    }

    private void readMeasuredStream(byte b) {
      fieldUnread--;
      measuredStream.read(b);
      if (fieldUnread == 0) {
        endField();
      }
    }

    private void endField() {
      var endsBetweenFields =
          switch (step) {
            case PACKED -> varintBytes == 0;
            case MEASURED_STREAM -> measuredStream.isBetweenFields();
            default -> true;
          };
      step = Step.TAG;
      packed = null;
      measuredStream = null;
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
