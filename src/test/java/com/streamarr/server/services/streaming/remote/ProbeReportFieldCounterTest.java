package com.streamarr.server.services.streaming.remote;

import static com.streamarr.server.services.streaming.remote.ReportingWorker.intervals;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.invalidMedia;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.reportWithResultOfLength;
import static com.streamarr.server.services.streaming.remote.ReportingWorker.withKeyframes;
import static com.streamarr.server.services.streaming.remote.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.UnknownFieldSet;
import com.google.protobuf.WireFormat;
import com.streamarr.server.services.streaming.remote.ProbeReportFieldCounter.BrokenRule;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Probe Report Field Counter Tests")
class ProbeReportFieldCounterTest {

  private static final int RESULT_LENGTH_LIMIT = 64 * 1024;
  private static final ProbeReportLimits LIMITS =
      ProbeReportLimits.builder()
          .keyframeLimit(3)
          .measuredKeyframeIntervalLimit(4)
          .resultLengthLimitBytes(RESULT_LENGTH_LIMIT)
          .build();
  // Long enough for every broken encoding below, so that only the encoding breaks a rule.
  private static final int STATED_LENGTH = 32;
  // Long enough for a result beyond the result length limit, so that only the result breaks a rule.
  private static final long ROOM_FOR_A_LONGER_RESULT = 2L * RESULT_LENGTH_LIMIT;
  // Long enough for every part of a probe report that tests the decoded size limit. With the
  // shortest result length limit the room is 112 KiB, and 32 times the room, 3.5 MiB, is the limit.
  private static final int LENGTH_OF_DECODED_PARTS = 64 * 1024;
  // Long enough that more empty measured streams than half the result length limit keep the
  // decoded size limit, so that only their count breaks a rule.
  private static final long ROOM_FOR_EMPTY_MEASURED_STREAMS = 1024L * 1024;
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
    var tagAndLength =
        tagAndLength(ProbeAttemptReport.RESULT_FIELD_NUMBER, RESULT_LENGTH_LIMIT + 1);
    var counter = counter(ROOM_FOR_A_LONGER_RESULT);

    // The length takes three bytes.
    var tagAndFirstByteOfLength = counter.read(tagAndLength.substring(0, 3));
    var lastByteOfLength = counter.read(tagAndLength.substring(3));

    assertThat(tagAndLength.size()).isEqualTo(4);
    assertThat(tagAndFirstByteOfLength).isEmpty();
    assertThat(lastByteOfLength).contains(BrokenRule.RESULT_LENGTH);
  }

  @Test
  @DisplayName("Should keep a result when it is exactly as long as the result length limit")
  void shouldKeepResultWhenItIsExactlyAsLongAsResultLengthLimit() {
    var bytes = reportWithResultOfLength(REQUEST, RESULT_LENGTH_LIMIT).build().toByteString();
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
    var first = reportWithResultOfLength(REQUEST, 100).build().toByteString();
    var second =
        tagAndLength(ProbeAttemptReport.RESULT_FIELD_NUMBER, RESULT_LENGTH_LIMIT - 100 + 1);
    var counter = counter(ROOM_FOR_A_LONGER_RESULT);

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
        arguments("field number zero", bytes(0x02), BrokenRule.INVALID_TAG),
        arguments(
            "a varint of eleven bytes in a packed run",
            bytes(0x12, 11).concat(repeated(0x80, 10)),
            BrokenRule.OVERLONG_VARINT),
        arguments(
            "a packed run that ends inside an entry",
            bytes(0x12, 1, 0x80),
            BrokenRule.ENDS_INSIDE_A_FIELD),
        arguments(
            "a measured stream that ends inside one of its fields",
            bytes(0x22, 1, 0x10),
            BrokenRule.ENDS_INSIDE_A_FIELD),
        arguments(
            "a field of a measured stream that is longer than the measured stream",
            bytes(0x22, 2, 0x12, 1),
            BrokenRule.FIELD_PAST_ITS_END),
        arguments("a group inside a measured stream", bytes(0x22, 1, 0x0b), BrokenRule.GROUP),
        arguments("a group inside the result", bytes(0x0a, 1, 0x0b), BrokenRule.GROUP),
        arguments(
            "a varint of eleven bytes in the result",
            bytes(0x0a, 11, 0x10).concat(repeated(0xff, 10)),
            BrokenRule.OVERLONG_VARINT),
        arguments(
            "a field of the result that is longer than the result",
            bytes(0x0a, 2, 0x1a, 1),
            BrokenRule.FIELD_PAST_ITS_END),
        arguments(
            "a stream of the result that ends inside one of its fields",
            bytes(0x0a, 5, 0x1a, 3, 0x12, 1, 0x08),
            BrokenRule.ENDS_INSIDE_A_FIELD),
        arguments(
            "the result's probe version with a length",
            bytes(0x0a, 2, 0x12),
            BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a stream of the result as a fixed32 value",
            bytes(0x0a, 6, 0x1a, 4, 0x15),
            BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a varint of eleven bytes in an undeclared field",
            bytes(0x48).concat(repeated(0xff, 10)),
            BrokenRule.OVERLONG_VARINT),
        arguments(
            "an undeclared field longer than the probe report",
            bytes(0x4a, STATED_LENGTH - 1),
            BrokenRule.FIELD_PAST_ITS_END),
        arguments(
            "an undeclared field of a measured stream longer than the measured stream",
            bytes(0x22, 2, 0x4a, 1),
            BrokenRule.FIELD_PAST_ITS_END),
        arguments("the result as a varint", bytes(0x08), BrokenRule.WRONG_WIRE_TYPE),
        arguments("the result as a fixed64 value", bytes(0x09), BrokenRule.WRONG_WIRE_TYPE),
        arguments("the result as a fixed32 value", bytes(0x0d), BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a keyframe presentation time delta as a fixed64 value",
            bytes(0x11),
            BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a keyframe decode time delta as a fixed32 value",
            bytes(0x1d),
            BrokenRule.WRONG_WIRE_TYPE),
        arguments("a measured stream as a varint", bytes(0x20), BrokenRule.WRONG_WIRE_TYPE),
        arguments("a measured stream as a fixed64 value", bytes(0x21), BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a measured stream's index with a length",
            bytes(0x22, 2, 0x0a),
            BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a measured stream's index as a fixed32 value",
            bytes(0x22, 5, 0x0d),
            BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a measured keyframe interval's bytes as a fixed64 value",
            bytes(0x22, 9, 0x11),
            BrokenRule.WRONG_WIRE_TYPE),
        arguments(
            "a measured keyframe interval's packet count as a fixed32 value",
            bytes(0x22, 5, 0x1d),
            BrokenRule.WRONG_WIRE_TYPE));
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
        arguments("a fixed64 value", bytes(0x49, 0, 0, 0)),
        arguments("a fixed32 value", bytes(0x4d, 0)));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(CountedList.class)
  @DisplayName(
      "Should break the list's limit at the byte that ends its first entry beyond the limit when"
          + " the probe report arrives split at any byte")
  void shouldBreakListsLimitAtByteThatEndsItsFirstEntryBeyondLimitWhenReportArrivesSplitAtAnyByte(
      CountedList list) {
    // The last byte ends the first entry beyond the limit.
    var bytes = list.beyondTheLimit().toByteString();

    for (var split = 0; split < bytes.size(); split++) {
      var counter = counterOf(bytes);

      assertThat(counter.read(bytes.substring(0, split))).as("bytes before %d", split).isEmpty();
      assertThat(counter.read(bytes.substring(split)))
          .as("bytes from %d", split)
          .contains(list.rule);
    }
  }

  private enum CountedList {
    KEYFRAME_PRESENTATION_TIME_DELTAS(BrokenRule.KEYFRAMES) {
      @Override
      ProbeAttemptReport beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addAllKeyframePresentationTimeDeltas(
                Collections.nCopies(LIMITS.keyframeLimit() + 1, 90_000L))
            .build();
      }
    },
    KEYFRAME_DECODE_TIME_DELTAS(BrokenRule.KEYFRAMES) {
      @Override
      ProbeAttemptReport beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addAllKeyframeDecodeTimeDeltas(
                Collections.nCopies(LIMITS.keyframeLimit() + 1, 90_000L))
            .build();
      }
    },
    KEYFRAME_INTERVAL_BYTES(BrokenRule.MEASURED_KEYFRAME_INTERVALS) {
      @Override
      ProbeAttemptReport beyondTheLimit() {
        // Two measured streams share the intervals.
        return ProbeAttemptReport.newBuilder()
            .addStreamMeasurementIntervals(
                StreamMeasurementIntervals.newBuilder()
                    .setStreamIndex(0)
                    .addAllKeyframeIntervalBytes(
                        Collections.nCopies(LIMITS.measuredKeyframeIntervalLimit(), 1_000)))
            .addStreamMeasurementIntervals(
                StreamMeasurementIntervals.newBuilder()
                    .setStreamIndex(1)
                    .addKeyframeIntervalBytes(1_000))
            .build();
      }
    },
    KEYFRAME_INTERVAL_PACKET_COUNTS(BrokenRule.MEASURED_KEYFRAME_INTERVALS) {
      @Override
      ProbeAttemptReport beyondTheLimit() {
        return ProbeAttemptReport.newBuilder()
            .addStreamMeasurementIntervals(
                StreamMeasurementIntervals.newBuilder()
                    .setStreamIndex(0)
                    .addAllKeyframeIntervalPacketCounts(
                        Collections.nCopies(LIMITS.measuredKeyframeIntervalLimit(), 1_000)))
            .addStreamMeasurementIntervals(
                StreamMeasurementIntervals.newBuilder()
                    .setStreamIndex(1)
                    .addKeyframeIntervalPacketCounts(1_000))
            .build();
      }
    };

    private final BrokenRule rule;

    CountedList(BrokenRule rule) {
      this.rule = rule;
    }

    /** A probe report whose last byte ends the first entry of the list beyond its limit. */
    abstract ProbeAttemptReport beyondTheLimit();
  }

  @Test
  @DisplayName(
      "Should count the keyframes of each list, packed or not, over every occurrence of the list"
          + " in any order")
  void shouldCountKeyframesOfEachListPackedOrNotOverEveryOccurrenceOfListInAnyOrder()
      throws IOException {
    var out = ByteString.newOutput();
    var fields = CodedOutputStream.newInstance(out);
    // One presentation time delta on its own, then three decode time deltas in a packed run.
    fields.writeSInt64(ProbeAttemptReport.KEYFRAME_PRESENTATION_TIME_DELTAS_FIELD_NUMBER, 1);
    packed(fields, ProbeAttemptReport.KEYFRAME_DECODE_TIME_DELTAS_FIELD_NUMBER, 3);
    fields.writeByteArray(ProbeAttemptReport.RESULT_FIELD_NUMBER, new byte[0]);
    // Two more presentation time deltas in a packed run reach the limit of three.
    packed(fields, ProbeAttemptReport.KEYFRAME_PRESENTATION_TIME_DELTAS_FIELD_NUMBER, 2);
    fields.flush();
    var atTheLimits = out.toByteString();
    var fourthPresentationTimeDelta = bytes(0x10, 0x02);
    var counter = counter(atTheLimits.size() + fourthPresentationTimeDelta.size());

    var read = counter.read(atTheLimits);
    var beyondTheLimit = counter.read(fourthPresentationTimeDelta);

    assertThat(read).isEmpty();
    assertThat(beyondTheLimit).contains(BrokenRule.KEYFRAMES);
  }

  @Test
  @DisplayName(
      "Should count the measured keyframe intervals of each list, packed or not, over every"
          + " measured stream")
  void shouldCountMeasuredKeyframeIntervalsOfEachListPackedOrNotOverEveryMeasuredStream()
      throws IOException {
    // Three interval bytes and four interval packet counts, packed or not, in two streams.
    var first = new ByteArrayOutputStream();
    var firstFields = CodedOutputStream.newInstance(first);
    firstFields.writeUInt32(StreamMeasurementIntervals.KEYFRAME_INTERVAL_BYTES_FIELD_NUMBER, 1);
    packed(firstFields, StreamMeasurementIntervals.KEYFRAME_INTERVAL_PACKET_COUNTS_FIELD_NUMBER, 3);
    firstFields.writeUInt32(StreamMeasurementIntervals.STREAM_INDEX_FIELD_NUMBER, 0);
    firstFields.flush();
    var second = new ByteArrayOutputStream();
    var secondFields = CodedOutputStream.newInstance(second);
    packed(secondFields, StreamMeasurementIntervals.KEYFRAME_INTERVAL_BYTES_FIELD_NUMBER, 2);
    secondFields.writeUInt32(
        StreamMeasurementIntervals.KEYFRAME_INTERVAL_PACKET_COUNTS_FIELD_NUMBER, 1);
    secondFields.flush();
    var out = ByteString.newOutput();
    var fields = CodedOutputStream.newInstance(out);
    fields.writeByteArray(
        ProbeAttemptReport.STREAM_MEASUREMENT_INTERVALS_FIELD_NUMBER, first.toByteArray());
    fields.writeByteArray(
        ProbeAttemptReport.STREAM_MEASUREMENT_INTERVALS_FIELD_NUMBER, second.toByteArray());
    fields.flush();
    var atTheLimits = out.toByteString();
    // A third stream with one interval packet count goes beyond the limit of four.
    var fifthPacketCount = bytes(0x22, 2, 0x18, 0x01);
    var counter = counter(atTheLimits.size() + fifthPacketCount.size());

    var read = counter.read(atTheLimits);
    var beyondTheLimit = counter.read(fifthPacketCount);

    assertThat(read).isEmpty();
    assertThat(beyondTheLimit).contains(BrokenRule.MEASURED_KEYFRAME_INTERVALS);
  }

  @Test
  @DisplayName(
      "Should break the measured stream limit at the measured stream beyond half the result"
          + " length limit, and keep a probe report at it")
  void
      shouldBreakMeasuredStreamLimitAtMeasuredStreamBeyondHalfResultLengthLimitAndKeepReportAtIt() {
    var atTheLimit = repeated(emptyMeasuredStream(), RESULT_LENGTH_LIMIT / 2);
    var kept = counter(ROOM_FOR_EMPTY_MEASURED_STREAMS);
    var broken = counter(ROOM_FOR_EMPTY_MEASURED_STREAMS);

    var readAtTheLimit = broken.read(atTheLimit);
    var beyondTheLimit = broken.read(emptyMeasuredStream());

    assertThat(kept.read(atTheLimit)).isEmpty();
    assertThat(kept.end()).isEmpty();
    assertThat(readAtTheLimit).isEmpty();
    assertThat(beyondTheLimit).contains(BrokenRule.MEASURED_STREAMS);
  }

  @Test
  @DisplayName(
      "Should break the decoded size limit when a result within the result length limit holds"
          + " only empty streams")
  void shouldBreakDecodedSizeLimitWhenResultWithinResultLengthLimitHoldsOnlyEmptyStreams() {
    // The streams take all but the four bytes of the media's tag and length.
    var streams =
        Collections.nCopies((RESULT_LENGTH_LIMIT - 4) / 2, ProbeStreamInfo.getDefaultInstance());
    var bytes = reportOfMedia(ProbeMediaInfo.newBuilder().addAllStreams(streams));
    var counter = counterOf(bytes);

    var read = counter.read(bytes);

    assertThat(read).contains(BrokenRule.DECODED_SIZE);
  }

  @Test
  @DisplayName(
      "Should break the decoded size limit when the probe report holds numbers, texts and"
          + " messages that each keep it on their own")
  void shouldBreakDecodedSizeLimitWhenReportHoldsNumbersTextsAndMessagesThatEachKeepItAlone()
      throws IOException {
    // Of the decoded size limit, the numbers take about an eighth, the texts about an eighth and
    // the
    // messages about four fifths.
    var numbers =
        ProbeAttemptReport.newBuilder()
            .addAllKeyframePresentationTimeDeltas(Collections.nCopies(24_750, 1L))
            .build()
            .toByteString();
    var texts = reportOfContainer(repeated(bytes(0x0a, 1, 'x'), 9_250));
    var messages =
        reportOfMedia(
            ProbeMediaInfo.newBuilder()
                .addAllStreams(Collections.nCopies(6_425, ProbeStreamInfo.getDefaultInstance())));
    var allThree = numbers.concat(texts).concat(messages);

    var withoutNumbers = decodedSizeCounter().read(texts.concat(messages));
    var withoutTexts = decodedSizeCounter().read(numbers.concat(messages));
    var withoutMessages = decodedSizeCounter().read(numbers.concat(texts));
    var withAllThree = decodedSizeCounter().read(allThree);

    assertThat(allThree.size()).isLessThanOrEqualTo(LENGTH_OF_DECODED_PARTS);
    assertThat(withoutNumbers).isEmpty();
    assertThat(withoutTexts).isEmpty();
    assertThat(withoutMessages).isEmpty();
    assertThat(withAllThree).contains(BrokenRule.DECODED_SIZE);
  }

  @Test
  @DisplayName(
      "Should declare only varints in the repeated number fields of a probe report when the counter"
          + " reads every packed run as varints")
  void shouldDeclareOnlyVarintsInRepeatedNumberFieldsWhenCounterReadsEveryPackedRunAsVarints() {
    var fields = repeatedNumberFields(ProbeAttemptReport.getDescriptor(), new HashSet<>());

    assertThat(fields)
        .isNotEmpty()
        .allSatisfy(
            field ->
                assertThat(field.getLiteType().getWireType())
                    .as(field.getFullName())
                    .isEqualTo(WireFormat.WIRETYPE_VARINT));
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
    var tooLongResult =
        tagAndLength(ProbeAttemptReport.RESULT_FIELD_NUMBER, RESULT_LENGTH_LIMIT + 1);
    var counter = counter(ROOM_FOR_A_LONGER_RESULT);

    var uncounted = counter.read(out.toByteString());
    var lengthOfResult = counter.read(tooLongResult);

    assertThat(uncounted).isEmpty();
    assertThat(lengthOfResult).contains(BrokenRule.RESULT_LENGTH);
  }

  @Test
  @DisplayName(
      "Should go on counting a measured stream's intervals after a field of the measured stream"
          + " that the contract does not declare")
  void shouldGoOnCountingMeasuredStreamsIntervalsAfterFieldOfMeasuredStreamThatIsNotDeclared()
      throws IOException {
    var stream = ByteString.newOutput();
    var streamFields = CodedOutputStream.newInstance(stream);
    streamFields.writeUInt64(9, 150);
    streamFields.writeByteArray(10, new byte[] {1, 2, 3});
    packed(
        streamFields,
        StreamMeasurementIntervals.KEYFRAME_INTERVAL_BYTES_FIELD_NUMBER,
        LIMITS.measuredKeyframeIntervalLimit() + 1);
    streamFields.flush();
    var out = ByteString.newOutput();
    var fields = CodedOutputStream.newInstance(out);
    fields.writeBytes(
        ProbeAttemptReport.STREAM_MEASUREMENT_INTERVALS_FIELD_NUMBER, stream.toByteString());
    fields.flush();
    var bytes = out.toByteString();

    assertThat(counterOf(bytes).read(bytes)).contains(BrokenRule.MEASURED_KEYFRAME_INTERVALS);
  }

  @Test
  @DisplayName(
      "Should go on reading the result when a stream of the result holds a field that the contract"
          + " does not declare")
  void shouldGoOnReadingResultWhenStreamOfResultHoldsFieldThatIsNotDeclared() {
    var stream =
        ProbeStreamInfo.newBuilder()
            .setIndex(0)
            .setUnknownFields(
                UnknownFieldSet.newBuilder()
                    .addField(
                        900,
                        UnknownFieldSet.Field.newBuilder()
                            .addLengthDelimited(ByteString.copyFromUtf8("removed"))
                            .addFixed64(1)
                            .build())
                    .build());
    var undeclared =
        ProbeAttemptReport.newBuilder()
            .setResult(
                ProbeAttemptResult.newBuilder()
                    .setMedia(ProbeMediaInfo.newBuilder().addStreams(stream)))
            .build()
            .toByteString();
    var groupAfterTheStream = bytes(0x0b);
    var counter = counter(undeclared.size() + groupAfterTheStream.size());

    var read = counter.read(undeclared);
    var after = counter.read(groupAfterTheStream);

    assertThat(read).isEmpty();
    assertThat(after).contains(BrokenRule.GROUP);
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

  /**
   * A counter of a probe report of the decoded parts' length at the shortest result length limit,
   * whose keyframe limit no part reaches.
   */
  private static ProbeReportFieldCounter decodedSizeCounter() {
    var limits =
        ProbeReportLimits.builder()
            .keyframeLimit(100_000)
            .resultLengthLimitBytes(48 * 1024)
            .build();
    return new ProbeReportFieldCounter(limits, LENGTH_OF_DECODED_PARTS);
  }

  private static ByteString reportOfMedia(ProbeMediaInfo.Builder media) {
    return ProbeAttemptReport.newBuilder()
        .setResult(ProbeAttemptResult.newBuilder().setMedia(media))
        .build()
        .toByteString();
  }

  /** A probe report whose result's container holds these encoded fields. */
  private static ByteString reportOfContainer(ByteString fields) throws IOException {
    var container = lengthDelimited(ProbeMediaInfo.CONTAINER_FIELD_NUMBER, fields);
    var media = lengthDelimited(ProbeAttemptResult.MEDIA_FIELD_NUMBER, container);
    return lengthDelimited(ProbeAttemptReport.RESULT_FIELD_NUMBER, media);
  }

  private static ByteString lengthDelimited(int fieldNumber, ByteString content)
      throws IOException {
    return tagAndLength(fieldNumber, content.size()).concat(content);
  }

  /** The repeated fields of numbers in the message and in every message that it holds. */
  private static List<FieldDescriptor> repeatedNumberFields(
      Descriptor message, Set<Descriptor> visited) {
    var fields = new ArrayList<FieldDescriptor>();
    if (!visited.add(message)) {
      return fields;
    }

    for (var field : message.getFields()) {
      if (field.isPackable()) {
        fields.add(field);
      }

      if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
        fields.addAll(repeatedNumberFields(field.getMessageType(), visited));
      }
    }

    return fields;
  }

  /** Writes a packed run of this many entries, each one byte. */
  private static void packed(CodedOutputStream fields, int fieldNumber, int entries)
      throws IOException {
    fields.writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
    fields.writeUInt32NoTag(entries);
    for (var entry = 0; entry < entries; entry++) {
      fields.writeUInt32NoTag(1);
    }
  }

  private static ProbeReportFieldCounter counterOf(ByteString report) {
    return counter(report.size());
  }

  private static ProbeReportFieldCounter counter(long statedLength) {
    return new ProbeReportFieldCounter(LIMITS, statedLength);
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

  private static ByteString repeated(ByteString bytes, int times) {
    return ByteString.copyFrom(Collections.nCopies(times, bytes));
  }

  /** A measured stream that holds no field: its tag and a length of zero. */
  private static ByteString emptyMeasuredStream() {
    return ProbeAttemptReport.newBuilder()
        .addStreamMeasurementIntervals(StreamMeasurementIntervals.getDefaultInstance())
        .build()
        .toByteString();
  }
}
