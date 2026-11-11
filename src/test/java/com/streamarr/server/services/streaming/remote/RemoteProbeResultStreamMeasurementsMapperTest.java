package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.v1.KeyframePositionSource;
import com.streamarr.transcode.v1.KeyframePositions;
import com.streamarr.transcode.v1.MeasuredStream;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.StreamMeasurementIntervals;
import com.streamarr.transcode.v1.StreamMeasurementSource;
import com.streamarr.transcode.v1.StreamMeasurements;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Stream Measurements Mapper Tests")
class RemoteProbeResultStreamMeasurementsMapperTest {

  private static final int VIDEO = 0;
  private static final int AUDIO = 1;
  private static final int SUBTITLE = 2;
  private static final int COVER_ART = 3;

  @Test
  @DisplayName("Should report no measurement when the worker measured no stream")
  void shouldReportNoMeasurementWhenWorkerMeasuredNoStream() {
    var report = report(result(media().setKeyframes(knownKeyframes())), List.of());

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report);

    assertThat(outcome.streamMeasurements()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should accept a measurement without another video stream when it covers the chosen video"
          + " stream and every audio stream")
  void shouldAcceptMeasurementWithoutAnotherVideoStreamWhenItCoversChosenVideoAndEveryAudio() {
    var report =
        report(
            result(
                media()
                    .addStreams(
                        ProbeStreamInfo.newBuilder().setIndex(COVER_ART).setCodecType("video"))
                    .setKeyframes(knownKeyframes())
                    .setStreamMeasurements(measurements(video(30), audio(3)))),
            List.of(intervals(VIDEO, 10, 20), intervals(AUDIO, 1, 2)));

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report);

    var measured = outcome.streamMeasurements().orElseThrow();
    assertThat(measured.stream(COVER_ART)).isEmpty();
    assertThat(measured.stream(VIDEO).orElseThrow().keyframeIntervalBytes())
        .isEqualTo(KeyframeIntervalCounts.of(10, 20));
    assertThat(measured.stream(AUDIO).orElseThrow().keyframeIntervalBytes())
        .isEqualTo(KeyframeIntervalCounts.of(1, 2));
  }

  @Test
  @DisplayName("Should keep each interval's packets when the worker reports them")
  void shouldKeepEachIntervalsPacketsWhenWorkerReportsThem() {
    var report =
        report(
            measurements(video(30).setPacketCount(5), audio(3).setPacketCount(7)),
            List.of(
                intervalsOf(VIDEO)
                    .addAllKeyframeIntervalBytes(List.of(10, 20))
                    .addAllKeyframeIntervalPacketCounts(List.of(2, 3))
                    .build(),
                intervalsOf(AUDIO)
                    .addAllKeyframeIntervalBytes(List.of(1, 2))
                    .addAllKeyframeIntervalPacketCounts(List.of(3, 4))
                    .build()));

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report);

    var measured = outcome.streamMeasurements().orElseThrow();
    assertThat(measured.stream(VIDEO).orElseThrow().keyframeIntervalPacketCounts())
        .isEqualTo(KeyframeIntervalCounts.of(2, 3));
    assertThat(measured.stream(AUDIO).orElseThrow().keyframeIntervalPacketCounts())
        .isEqualTo(KeyframeIntervalCounts.of(3, 4));
  }

  @Test
  @DisplayName("Should read each interval's packets as unsigned when a count uses the top bit")
  void shouldReadEachIntervalsPacketsAsUnsignedWhenCountUsesTopBit() {
    var report =
        report(
            measurements(video(30).setPacketCount(0x1_0000_0001L), audio(3)),
            List.of(
                intervalsOf(VIDEO)
                    .addAllKeyframeIntervalBytes(List.of(10, 20))
                    .addAllKeyframeIntervalPacketCounts(List.of(-1, 2))
                    .build(),
                intervals(AUDIO, 1, 2)));

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report);

    assertThat(
            outcome.streamMeasurements().orElseThrow().stream(VIDEO)
                .orElseThrow()
                .keyframeIntervalPacketCounts())
        .isEqualTo(KeyframeIntervalCounts.of(0xFFFF_FFFFL, 2));
  }

  // The contract and the domain share this type name.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest(name = "{1} beside {0}")
  @MethodSource("stepsThatTheKeyframePositionsAllow")
  @DisplayName(
      "Should keep the measurement's source when the worker names the step that read the"
          + " keyframe positions")
  void shouldKeepMeasurementsSourceWhenWorkerNamesStepThatReadKeyframePositions(
      KeyframePositionSource keyframes,
      StreamMeasurementSource reported,
      com.streamarr.server.domain.streaming.StreamMeasurementSource expected) {
    var report =
        report(
            keyframesFrom(keyframes),
            measurements(video(30), audio(3)).setSource(reported),
            List.of(intervals(VIDEO, 10, 20), intervals(AUDIO, 1, 2)));

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report);

    assertThat(outcome.streamMeasurements().orElseThrow().source()).isEqualTo(expected);
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Stream<Arguments> stepsThatTheKeyframePositionsAllow() {
    return Stream.of(
        arguments(
            KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX,
            StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_SAMPLE_TABLE,
            com.streamarr.server.domain.streaming.StreamMeasurementSource.SAMPLE_TABLE),
        arguments(
            KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN,
            StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_KEYFRAME_PACKET_SCAN,
            com.streamarr.server.domain.streaming.StreamMeasurementSource.KEYFRAME_PACKET_SCAN));
  }

  @ParameterizedTest(name = "{1} beside {0}")
  @MethodSource("stepsThatTheKeyframePositionsDoNotAllow")
  @DisplayName(
      "Should fail the probe as a contract violation when the measurement names a step that did"
          + " not read the keyframe positions")
  void shouldFailProbeAsContractViolationWhenMeasurementNamesStepThatDidNotReadKeyframePositions(
      KeyframePositionSource keyframes, StreamMeasurementSource reported) {
    var report =
        report(
            keyframesFrom(keyframes),
            measurements(video(30), audio(3)).setSource(reported),
            List.of(intervals(VIDEO, 10, 20), intervals(AUDIO, 1, 2)));
    var mapper = new RemoteProbeResultMapper();

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class)
        .hasMessage(
            "Worker probe measurement names a step that its keyframe positions do not allow");
  }

  static Stream<Arguments> stepsThatTheKeyframePositionsDoNotAllow() {
    return Stream.of(
        arguments(
            KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN,
            StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_SAMPLE_TABLE),
        arguments(
            KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX,
            StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_KEYFRAME_PACKET_SCAN),
        arguments(
            KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX,
            StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_BITRATE_SCAN),
        arguments(
            KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN,
            StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_BITRATE_SCAN));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractViolations")
  @DisplayName("Should fail the probe as a contract violation when the measurement breaks a rule")
  void shouldFailProbeAsContractViolationWhenMeasurementBreaksRule(
      String rule, ProbeAttemptReport report, String message) {
    var mapper = new RemoteProbeResultMapper();

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class)
        .hasMessage(message);
  }

  static Stream<Arguments> contractViolations() {
    var bothIntervals = List.of(intervals(VIDEO, 10, 20), intervals(AUDIO, 1, 2));
    return Stream.of(
        arguments(
            "no keyframe positions",
            ProbeAttemptReport.newBuilder()
                .setResult(
                    result(
                        media()
                            .setKeyframes(
                                ProbeKeyframes.newBuilder()
                                    .setPacketScanRequired(
                                        KeyframePacketScanReason
                                            .KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX))
                            .setStreamMeasurements(measurements(video(30)))))
                .addStreamMeasurementIntervals(intervals(VIDEO, 30))
                .build(),
            "Worker probe measured streams without keyframe positions"),
        arguments(
            "a stream measured twice",
            report(measurements(video(30), video(30)), List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measured one stream twice"),
        arguments(
            "no chosen video stream",
            report(measurements(audio(3)), List.of(intervals(AUDIO, 1, 2))),
            "Worker probe measurement leaves out the chosen video stream"),
        arguments(
            "an audio stream left out",
            report(measurements(video(30)), List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measurement leaves out an audio stream"),
        arguments(
            "intervals of an unmeasured stream",
            report(measurements(video(30)), bothIntervals),
            "Worker probe sent keyframe interval bytes for a stream it did not measure"),
        arguments(
            "a subtitle stream",
            report(
                measurements(video(30), stream(SUBTITLE, 3)),
                List.of(intervals(VIDEO, 10, 20), intervals(SUBTITLE, 1, 2))),
            "Worker probe measured a stream that is neither video nor audio"),
        arguments(
            "bytes beyond a signed 64-bit count",
            report(
                measurements(video(30).setMeasuredBytes(-1L)), List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measured bytes, packets, or duration exceed the contract's range"),
        arguments(
            "a duration beyond a signed 64-bit count",
            report(
                measurements(video(30).setMeasuredDuration(-1L)),
                List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measured bytes, packets, or duration exceed the contract's range"),
        arguments(
            "a packet count beyond a signed 64-bit count",
            report(
                measurements(video(30).setPacketCount(-1L), audio(3)),
                List.of(intervals(VIDEO, 10, 20), intervals(AUDIO, 1, 2))),
            "Worker probe measured bytes, packets, or duration exceed the contract's range"),
        arguments(
            "a zero time base",
            report(
                measurements(video(30).setTimeBaseDenominator(0)),
                List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measured stream time base is not positive"),
        arguments(
            "a zero time base numerator",
            report(
                measurements(video(30).setTimeBaseNumerator(0)), List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measured stream time base is not positive"),
        arguments(
            "no intervals",
            report(measurements(video(30)), List.of()),
            "Worker probe measured stream's intervals differ from its keyframes"),
        arguments(
            "fewer intervals than keyframes",
            report(measurements(video(30)), List.of(intervals(VIDEO, 30))),
            "Worker probe measured stream's intervals differ from its keyframes"),
        arguments(
            "intervals that differ from the measured bytes",
            report(measurements(video(31)), List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measured stream's intervals differ from its measured bytes"),
        arguments(
            "fewer interval packet counts than interval bytes",
            report(
                measurements(video(30)),
                List.of(
                    intervalsOf(VIDEO)
                        .addAllKeyframeIntervalBytes(List.of(10, 20))
                        .addKeyframeIntervalPacketCounts(2)
                        .build())),
            "Worker probe measured stream's intervals differ from its keyframes"),
        arguments(
            "more interval packet counts than interval bytes",
            report(
                measurements(video(30)),
                List.of(
                    intervalsOf(VIDEO)
                        .addAllKeyframeIntervalBytes(List.of(10, 20))
                        .addAllKeyframeIntervalPacketCounts(List.of(1, 1, 0))
                        .build())),
            "Worker probe measured stream's intervals differ from its keyframes"),
        arguments(
            "interval packet counts that differ from the packet count",
            report(
                measurements(video(30)),
                List.of(
                    intervalsOf(VIDEO)
                        .addAllKeyframeIntervalBytes(List.of(10, 20))
                        .addAllKeyframeIntervalPacketCounts(List.of(1, 2))
                        .build())),
            "Worker probe measured stream's intervals differ from its packet count"),
        arguments(
            "no source",
            report(
                measurements(video(30))
                    .setSource(StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_UNSPECIFIED),
                List.of(intervals(VIDEO, 10, 20))),
            "Worker probe measurement names no source"));
  }

  private static ProbeAttemptReport report(
      StreamMeasurements.Builder measurements, List<StreamMeasurementIntervals> intervals) {
    return report(knownKeyframes(), measurements, intervals);
  }

  private static ProbeAttemptReport report(
      ProbeKeyframes.Builder keyframes,
      StreamMeasurements.Builder measurements,
      List<StreamMeasurementIntervals> intervals) {
    return report(
        result(media().setKeyframes(keyframes).setStreamMeasurements(measurements)),
        intervals);
  }

  /** A report with the two keyframe positions that {@link #knownKeyframes()} counts. */
  private static ProbeAttemptReport report(
      ProbeAttemptResult result, List<StreamMeasurementIntervals> intervals) {
    return ProbeAttemptReport.newBuilder()
        .setResult(result)
        .addAllKeyframePresentationTimeDeltas(List.of(0L, 90_000L))
        .addAllKeyframeDecodeTimeDeltas(List.of(0L, 90_000L))
        .addAllStreamMeasurementIntervals(intervals)
        .build();
  }

  private static ProbeAttemptResult result(ProbeMediaInfo.Builder media) {
    return ProbeAttemptResult.newBuilder().setMedia(media).build();
  }

  private static ProbeMediaInfo.Builder media() {
    return ProbeMediaInfo.newBuilder()
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(VIDEO).setCodecType("video"))
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(AUDIO).setCodecType("audio"))
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(SUBTITLE).setCodecType("subtitle"));
  }

  private static ProbeKeyframes.Builder knownKeyframes() {
    return keyframesFrom(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX);
  }

  private static ProbeKeyframes.Builder keyframesFrom(KeyframePositionSource source) {
    return ProbeKeyframes.newBuilder()
        .setPositions(
            KeyframePositions.newBuilder()
                .setStreamIndex(VIDEO)
                .setTimeBaseNumerator(1)
                .setTimeBaseDenominator(90_000)
                .setSource(source)
                .setKeyframeCount(2));
  }

  private static StreamMeasurements.Builder measurements(MeasuredStream.Builder... streams) {
    var measurements =
        StreamMeasurements.newBuilder()
            .setSource(StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_SAMPLE_TABLE);
    for (var stream : streams) {
      measurements.addStreams(stream);
    }

    return measurements;
  }

  private static MeasuredStream.Builder video(long bytes) {
    return stream(VIDEO, bytes);
  }

  private static MeasuredStream.Builder audio(long bytes) {
    return stream(AUDIO, bytes);
  }

  private static MeasuredStream.Builder stream(int index, long bytes) {
    return MeasuredStream.newBuilder()
        .setStreamIndex(index)
        .setMeasuredBytes(bytes)
        .setPacketCount(2)
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(90_000)
        .setMeasuredDuration(180_000);
  }

  /** One packet in each interval. */
  private static StreamMeasurementIntervals intervals(int streamIndex, int... bytes) {
    var intervals = intervalsOf(streamIndex);
    for (var count : bytes) {
      intervals.addKeyframeIntervalBytes(count).addKeyframeIntervalPacketCounts(1);
    }

    return intervals.build();
  }

  private static StreamMeasurementIntervals.Builder intervalsOf(int streamIndex) {
    return StreamMeasurementIntervals.newBuilder().setStreamIndex(streamIndex);
  }
}
