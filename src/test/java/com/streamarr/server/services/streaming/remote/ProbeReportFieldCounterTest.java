package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.intervals;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.invalidMedia;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.withKeyframes;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import com.streamarr.server.services.streaming.remote.ProbeReportFieldCounter.BrokenRule;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Probe Report Field Counter Tests")
class ProbeReportFieldCounterTest {

  private static final int RESULT_LENGTH_LIMIT = 200;
  private static final ProbeReportLimits LIMITS =
      ProbeReportLimits.builder()
          .keyframeLimit(3)
          .measuredKeyframeIntervalLimit(4)
          .resultLengthLimitBytes(RESULT_LENGTH_LIMIT)
          .build();
  // Long enough for every broken encoding below, so that only the encoding breaks a rule.
  private static final int STATED_LENGTH = 32;
  private static final ProbeRequest REQUEST =
      ProbeRequest.newBuilder()
          .setProbeAttemptId(toProto(UUID.randomUUID()))
          .setProbeVersion(ReportingWorker.PROBE_VERSION)
          .build();

  @Test
  @DisplayName(
      "Should keep every rule when a probe report within the limits arrives split at any byte")
  void shouldKeepEveryRuleWhenProbeReportWithinLimitsArrivesSplitAtAnyByte() {
    var bytes = reportWithinLimits().toByteString();

    for (var split = 0; split <= bytes.size(); split++) {
      var counter = counterOf(bytes);

      assertThat(counter.read(bytes.substring(0, split))).as("bytes before %d", split).isEmpty();
      assertThat(counter.read(bytes.substring(split))).as("bytes from %d", split).isEmpty();
      assertThat(counter.end()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should keep every rule when a probe report within the limits arrives one byte at a time")
  void shouldKeepEveryRuleWhenProbeReportWithinLimitsArrivesOneByteAtATime() {
    var bytes = reportWithinLimits().toByteString();
    var counter = counterOf(bytes);

    for (var at = 0; at < bytes.size(); at++) {
      assertThat(counter.read(bytes.substring(at, at + 1))).as("byte %d", at).isEmpty();
    }

    assertThat(counter.end()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should break the result length limit once the length of a longer result is read, before"
          + " the result's bytes arrive")
  void shouldBreakResultLengthLimitOnceLengthOfLongerResultIsReadBeforeResultsBytesArrive()
      throws IOException {
    var tagAndLength = tagAndLength(ProbeAttemptReport.RESULT_FIELD_NUMBER, 201);
    var counter = counter(1_000);

    // The length of 201 takes two bytes.
    var tagAndFirstByteOfLength = counter.read(tagAndLength.substring(0, 2));
    var lastByteOfLength = counter.read(tagAndLength.substring(2));

    assertThat(tagAndLength.size()).isEqualTo(3);
    assertThat(tagAndFirstByteOfLength).isEmpty();
    assertThat(lastByteOfLength).contains(BrokenRule.RESULT_LENGTH);
  }

  @Test
  @DisplayName("Should keep a result when it is exactly as long as the result length limit")
  void shouldKeepResultWhenItIsExactlyAsLongAsResultLengthLimit() throws IOException {
    var bytes = lengthDelimited(ProbeAttemptReport.RESULT_FIELD_NUMBER, RESULT_LENGTH_LIMIT);
    var counter = counterOf(bytes);

    assertThat(counter.read(bytes)).isEmpty();
    assertThat(counter.end()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should add up the lengths of the result's fields when a probe report holds its result more"
          + " than once, between other fields")
  void shouldAddUpLengthsOfResultsFieldsWhenProbeReportHoldsItsResultMoreThanOnce()
      throws IOException {
    var keyframe = withKeyframes(ProbeAttemptReport.newBuilder(), 1).build().toByteString();
    var first = lengthDelimited(ProbeAttemptReport.RESULT_FIELD_NUMBER, 100);
    var second =
        tagAndLength(ProbeAttemptReport.RESULT_FIELD_NUMBER, RESULT_LENGTH_LIMIT - 100 + 1);
    var counter = counter(1_000);

    var beforeSecond = counter.read(keyframe.concat(first).concat(keyframe));
    var lengthOfSecond = counter.read(second);

    assertThat(beforeSecond).isEmpty();
    assertThat(lengthOfSecond).contains(BrokenRule.RESULT_LENGTH);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("brokenEncodings")
  @DisplayName(
      "Should break the wire format at the byte that shows the break when a probe report holds a"
          + " broken encoding")
  void shouldBreakWireFormatAtByteThatShowsBreakWhenProbeReportHoldsBrokenEncoding(
      String encoding, ByteString bytes, BrokenRule rule) {
    var counter = counter(STATED_LENGTH);
    var last = bytes.size() - 1;

    for (var at = 0; at < last; at++) {
      assertThat(counter.read(bytes.substring(at, at + 1))).as("byte %d", at).isEmpty();
    }

    assertThat(counter.read(bytes.substring(last))).contains(rule);
  }

  static Stream<Arguments> brokenEncodings() {
    return Stream.of(
        arguments("a tag of eleven bytes", repeated(0x80, 10), BrokenRule.OVERLONG_VARINT),
        arguments(
            "a varint of eleven bytes",
            bytes(0x10).concat(repeated(0xff, 10)),
            BrokenRule.OVERLONG_VARINT),
        arguments(
            "a length of eleven bytes",
            bytes(0x0a).concat(repeated(0x80, 10)),
            BrokenRule.OVERLONG_VARINT),
        arguments(
            "a length beyond the stated length of the probe report",
            bytes(0x0a, STATED_LENGTH - 1),
            BrokenRule.FIELD_PAST_ITS_END),
        arguments(
            "a length whose low 32 bits read as negative",
            bytes(0x0a, 0x80, 0x80, 0x80, 0x80, 0x08),
            BrokenRule.FIELD_PAST_ITS_END),
        arguments("the start of a group", bytes(0x0b), BrokenRule.GROUP),
        arguments("the end of a group", bytes(0x0c), BrokenRule.GROUP),
        arguments("wire type 6", bytes(0x0e), BrokenRule.INVALID_TAG),
        arguments("wire type 7", bytes(0x0f), BrokenRule.INVALID_TAG),
        arguments("field number zero", bytes(0x02), BrokenRule.INVALID_TAG));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unfinishedFields")
  @DisplayName("Should break the wire format at its end when a probe report ends inside a field")
  void shouldBreakWireFormatAtItsEndWhenProbeReportEndsInsideField(String field, ByteString bytes) {
    var counter = counterOf(bytes);

    var read = counter.read(bytes);

    assertThat(read).isEmpty();
    assertThat(counter.end()).contains(BrokenRule.ENDS_INSIDE_A_FIELD);
  }

  static Stream<Arguments> unfinishedFields() {
    return Stream.of(
        arguments("a tag", bytes(0x80)),
        arguments("a varint", bytes(0x10, 0x80)),
        arguments("a length", bytes(0x0a, 0x80)),
        arguments("a fixed64 value", bytes(0x11, 0, 0, 0)),
        arguments("a fixed32 value", bytes(0x15, 0)));
  }

  @Test
  @DisplayName(
      "Should read a length from the low 32 bits of its varint when the varint is longer, as the"
          + " decoder does")
  void shouldReadLengthFromLow32BitsOfItsVarintWhenVarintIsLongerAsDecoderDoes() throws Exception {
    // A result field whose length of 2^32 + 2 the decoder reads as 2: the probe version 1.
    var bytes = bytes(0x0a, 0x82, 0x80, 0x80, 0x80, 0x10, 0x10, 0x01);
    var counter = counterOf(bytes);

    assertThat(counter.read(bytes)).isEmpty();
    assertThat(counter.end()).isEmpty();
    assertThat(ProbeAttemptReport.parseFrom(bytes).getResult().getProbeVersion()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "Should go on reading after a field that it does not count when the field has any wire type")
  void shouldGoOnReadingAfterFieldThatItDoesNotCountWhenFieldHasAnyWireType() throws IOException {
    var out = ByteString.newOutput();
    var fields = CodedOutputStream.newInstance(out);
    fields.writeUInt64(9, 150);
    fields.writeFixed64(10, 1);
    fields.writeFixed32(11, 1);
    fields.writeByteArray(12, new byte[] {1, 2, 3});
    fields.flush();
    var tooLongResult = tagAndLength(ProbeAttemptReport.RESULT_FIELD_NUMBER, 201);
    var counter = counter(1_000);

    var uncounted = counter.read(out.toByteString());
    var lengthOfResult = counter.read(tooLongResult);

    assertThat(uncounted).isEmpty();
    assertThat(lengthOfResult).contains(BrokenRule.RESULT_LENGTH);
  }

  @Test
  @DisplayName("Should name the first broken rule again when it reads further bytes")
  void shouldNameFirstBrokenRuleAgainWhenItReadsFurtherBytes() {
    var counter = counter(STATED_LENGTH);
    counter.read(bytes(0x0b));

    var further = counter.read(bytes(0x0a, STATED_LENGTH - 1));

    assertThat(further).contains(BrokenRule.GROUP);
    assertThat(counter.end()).contains(BrokenRule.GROUP);
  }

  /** A probe report at the keyframe limit and the measured keyframe interval limit. */
  private static ProbeAttemptReport reportWithinLimits() {
    return withKeyframes(invalidMedia(REQUEST), LIMITS.keyframeLimit())
        .addStreamMeasurementIntervals(intervals(0, 2))
        .addStreamMeasurementIntervals(intervals(1, 2))
        .build();
  }

  private static ProbeReportFieldCounter counterOf(ByteString report) {
    return counter(report.size());
  }

  private static ProbeReportFieldCounter counter(long statedLength) {
    return new ProbeReportFieldCounter(LIMITS, statedLength);
  }

  /** A length-delimited field of this many bytes, each zero. */
  private static ByteString lengthDelimited(int fieldNumber, int length) throws IOException {
    var out = ByteString.newOutput();
    var field = CodedOutputStream.newInstance(out);
    field.writeByteArray(fieldNumber, new byte[length]);
    field.flush();
    return out.toByteString();
  }

  /** The tag and the length of a length-delimited field, without its bytes. */
  private static ByteString tagAndLength(int fieldNumber, int length) throws IOException {
    var out = ByteString.newOutput();
    var field = CodedOutputStream.newInstance(out);
    field.writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
    field.writeUInt32NoTag(length);
    field.flush();
    return out.toByteString();
  }

  private static ByteString bytes(int... values) {
    var bytes = new byte[values.length];
    for (var at = 0; at < values.length; at++) {
      bytes[at] = (byte) values[at];
    }

    return ByteString.copyFrom(bytes);
  }

  private static ByteString repeated(int value, int times) {
    var bytes = new byte[times];
    Arrays.fill(bytes, (byte) value);
    return ByteString.copyFrom(bytes);
  }
}
