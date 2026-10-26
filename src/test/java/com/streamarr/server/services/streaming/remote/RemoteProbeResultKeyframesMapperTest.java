package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.v1.KeyframePositionSource;
import com.streamarr.transcode.v1.KeyframePositions;
import com.streamarr.transcode.v1.KeyframesUnavailable;
import com.streamarr.transcode.v1.KeyframesUnavailableReason;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Result Keyframes Mapper Tests")
class RemoteProbeResultKeyframesMapperTest {

  private static final int VIDEO_STREAM_INDEX = 0;
  private static final int AUDIO_STREAM_INDEX = 1;

  @Test
  @DisplayName("Should report no keyframe knowledge when the probe version reports no keyframes")
  void shouldReportNoKeyframeKnowledgeWhenProbeVersionReportsNoKeyframes() {
    var result = ProbeAttemptResult.newBuilder().setMedia(media()).build();

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report(result));

    assertThat(outcome.keyframes()).isEqualTo(new KeyframeKnowledge.NotReported());
  }

  // The contract and the domain share these type names.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @Test
  @DisplayName("Should map keyframe positions to absolute times when a worker sends them in parts")
  void shouldMapKeyframePositionsToAbsoluteTimesWhenWorkerSendsThemInParts() {
    var report =
        positionsReport(
            positions().setKeyframeCount(3),
            part(List.of(3_750L, 180_000L), List.of(-3_750L, 180_000L)),
            part(List.of(176_250L), List.of(183_750L)));

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report);

    assertThat(outcome.keyframes())
        .isEqualTo(
            new KeyframeKnowledge.Known(
                com.streamarr.server.domain.streaming.KeyframePositions.builder()
                    .streamIndex(VIDEO_STREAM_INDEX)
                    .timeBaseNumerator(1)
                    .timeBaseDenominator(90_000)
                    .source(
                        com.streamarr.server.domain.streaming.KeyframePositionSource.PACKET_SCAN)
                    .keyframes(
                        List.of(
                            new KeyframePosition(3_750, -3_750),
                            new KeyframePosition(183_750, 176_250),
                            new KeyframePosition(360_000, 360_000)))
                    .build()));
  }

  // The contract and the domain share these type names.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @CsvSource({
    "KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX,NO_CONTAINER_INDEX",
    "KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX,INCOMPLETE_CONTAINER_INDEX",
    "KEYFRAME_PACKET_SCAN_REASON_UNREADABLE_LAST_INDEXED_KEYFRAME,UNREADABLE_LAST_INDEXED_KEYFRAME"
  })
  @DisplayName("Should require a packet scan when the worker reports that the index is unusable")
  void shouldRequirePacketScanWhenWorkerReportsThatIndexIsUnusable(
      KeyframePacketScanReason reported,
      com.streamarr.server.domain.streaming.KeyframePacketScanReason expected) {
    var report =
        report(
            result(ProbeKeyframes.newBuilder().setPacketScanRequired(reported)),
            part(List.of(0L), List.of(0L)));

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report);

    assertThat(outcome.keyframes()).isEqualTo(new KeyframeKnowledge.PacketScanRequired(expected));
  }

  // The contract and the domain share these type names.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  @ParameterizedTest
  @CsvSource({
    "KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP,KEYFRAME_WITHOUT_TIMESTAMP",
    "KEYFRAMES_UNAVAILABLE_REASON_NO_KEYFRAMES,NO_KEYFRAMES"
  })
  @DisplayName("Should report unavailable keyframes when the worker cannot report positions")
  void shouldReportUnavailableKeyframesWhenWorkerCannotReportPositions(
      KeyframesUnavailableReason reported,
      com.streamarr.server.domain.streaming.KeyframesUnavailableReason expected) {
    var result =
        result(
            ProbeKeyframes.newBuilder()
                .setUnavailable(KeyframesUnavailable.newBuilder().setReason(reported)));

    var outcome = (ProbeOutcome.Success) new RemoteProbeResultMapper().map(report(result));

    assertThat(outcome.keyframes()).isEqualTo(new KeyframeKnowledge.Unavailable(expected));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("contractViolations")
  @DisplayName("Should reject keyframes when the worker violates the keyframe contract")
  void shouldRejectKeyframesWhenWorkerViolatesKeyframeContract(
      String violation, ProbeAttemptReport report) {
    var mapper = new RemoteProbeResultMapper();

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  private static Stream<Arguments> contractViolations() {
    var onePosition = part(List.of(0L), List.of(0L));
    return Stream.of(
        arguments(
            "unequal list lengths",
            positionsReport(positions(), part(List.of(0L, 1L), List.of(0L)))),
        arguments(
            "zero time base numerator",
            positionsReport(positions().setTimeBaseNumerator(0), onePosition)),
        arguments(
            "zero time base denominator",
            positionsReport(positions().setTimeBaseDenominator(0), onePosition)),
        arguments(
            "time base denominator beyond the integer range",
            positionsReport(positions().setTimeBaseDenominator(-1), onePosition)),
        arguments(
            "repeated decode time",
            positionsReport(
                positions().setKeyframeCount(2), part(List.of(0L, 1L), List.of(0L, 0L)))),
        arguments(
            "decreasing decode time",
            positionsReport(
                positions().setKeyframeCount(2), part(List.of(0L, 1L), List.of(10L, -1L)))),
        arguments(
            "decode time beyond the long range",
            positionsReport(
                positions().setKeyframeCount(2),
                part(List.of(0L, 0L), List.of(Long.MAX_VALUE, 1L)))),
        arguments(
            "presentation time beyond the long range",
            positionsReport(
                positions().setKeyframeCount(2),
                part(List.of(Long.MAX_VALUE, 1L), List.of(0L, 1L)))),
        arguments(
            "fewer keyframes than the count",
            positionsReport(positions().setKeyframeCount(2), onePosition)),
        arguments(
            "more keyframes than the count",
            positionsReport(positions(), onePosition, onePosition)),
        arguments("no keyframes", positionsReport(positions().setKeyframeCount(0))),
        arguments(
            "unspecified source",
            positionsReport(
                positions().setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_UNSPECIFIED),
                onePosition)),
        arguments(
            "stream index of no video stream",
            positionsReport(positions().setStreamIndex(AUDIO_STREAM_INDEX), onePosition)),
        arguments(
            "unspecified packet scan reason",
            report(
                result(
                    ProbeKeyframes.newBuilder()
                        .setPacketScanRequired(
                            KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_UNSPECIFIED)))),
        arguments(
            "unrecognized packet scan reason",
            report(result(ProbeKeyframes.newBuilder().setPacketScanRequiredValue(999)))),
        arguments(
            "unspecified unavailable reason",
            report(
                result(
                    ProbeKeyframes.newBuilder()
                        .setUnavailable(KeyframesUnavailable.getDefaultInstance())))),
        arguments("keyframes without a result", report(result(ProbeKeyframes.newBuilder()))));
  }

  private static ProbeMediaInfo.Builder media() {
    return ProbeMediaInfo.newBuilder()
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(VIDEO_STREAM_INDEX).setCodecType("video"))
        .addStreams(
            ProbeStreamInfo.newBuilder().setIndex(AUDIO_STREAM_INDEX).setCodecType("audio"));
  }

  private static ProbeAttemptResult result(ProbeKeyframes.Builder keyframes) {
    return ProbeAttemptResult.newBuilder().setMedia(media().setKeyframes(keyframes)).build();
  }

  private static KeyframePositions.Builder positions() {
    return KeyframePositions.newBuilder()
        .setStreamIndex(VIDEO_STREAM_INDEX)
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(90_000)
        .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
        .setKeyframeCount(1);
  }

  private static ProbeAttemptReport positionsReport(
      KeyframePositions.Builder positions, ProbeKeyframePositionsPart... parts) {
    return report(result(ProbeKeyframes.newBuilder().setPositions(positions)), parts);
  }

  private static ProbeAttemptReport report(
      ProbeAttemptResult result, ProbeKeyframePositionsPart... parts) {
    return new ProbeAttemptReport(result, List.of(parts));
  }

  private static ProbeKeyframePositionsPart part(
      List<Long> presentationTimeDeltas, List<Long> decodeTimeDeltas) {
    return ProbeKeyframePositionsPart.newBuilder()
        .addAllPresentationTimeDeltas(presentationTimeDeltas)
        .addAllDecodeTimeDeltas(decodeTimeDeltas)
        .build();
  }
}
