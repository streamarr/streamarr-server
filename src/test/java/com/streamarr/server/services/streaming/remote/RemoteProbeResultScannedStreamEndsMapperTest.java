package com.streamarr.server.services.streaming.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.streamarr.server.domain.media.ProbeVersion;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.exceptions.ProbeContractViolationException;
import com.streamarr.server.fixtures.WorkerProbeResultFixture;
import com.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.v1.KeyframePositionSource;
import com.streamarr.transcode.v1.KeyframePositions;
import com.streamarr.transcode.v1.ProbeAttemptReport;
import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.v1.ProbeMediaInfo;
import com.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.v1.SourceStreamEnd;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Remote Probe Scanned Stream Ends Mapping Tests")
class RemoteProbeResultScannedStreamEndsMapperTest {

  private static final int VERSION_9 = ProbeVersion.FIRST_TO_REPORT_SCANNED_STREAM_ENDS;
  private static final int VERSION_8 = VERSION_9 - 1;
  private static final int VIDEO = 0;
  private static final int AUDIO = 1;
  private static final int COMMENTARY = 2;
  private static final int SECOND_VIDEO = 3;
  private static final int SUBTITLE = 4;

  private final RemoteProbeResultMapper mapper = new RemoteProbeResultMapper();

  @Test
  @DisplayName(
      "Should keep the end of the chosen video stream and of each audio stream when a probe version"
          + " 9 packet scan reports them")
  void shouldKeepTheEndOfTheChosenVideoStreamAndOfEachAudioStreamWhenAVersion9ScanReportsThem() {
    var media =
        scannedMedia()
            .addStreamEnds(end(VIDEO, 14, 166_666_666))
            .addStreamEnds(end(AUDIO, 14, 250_000_000))
            .addStreamEnds(end(COMMENTARY, 9, 0));

    var outcome = success(VERSION_9, media);

    assertThat(outcome.scannedStreamEnds())
        .extracting(end -> end.streamIndex(), end -> end.endTime())
        .containsExactly(
            tuple(VIDEO, Duration.ofSeconds(14, 166_666_666)),
            tuple(AUDIO, Duration.ofMillis(14_250)),
            tuple(COMMENTARY, Duration.ofSeconds(9)));
  }

  // The last keyframe presents 2 s into the media. A video stream that ends exactly there ends at
  // the least time that the contract allows.
  @Test
  @DisplayName("Should keep the chosen video stream's end when it ends at its last keyframe")
  void shouldKeepTheChosenVideoStreamsEndWhenItEndsAtItsLastKeyframe() {
    var outcome = success(VERSION_9, scannedMedia().addStreamEnds(end(VIDEO, 2, 0)));

    assertThat(outcome.scannedStreamEnds())
        .extracting(end -> end.streamIndex(), end -> end.endTime())
        .containsExactly(tuple(VIDEO, Duration.ofSeconds(2)));
  }

  @Test
  @DisplayName("Should keep no stream end when a probe version 9 packet scan reports none")
  void shouldKeepNoStreamEndWhenAProbeVersion9PacketScanReportsNone() {
    var outcome = success(VERSION_9, scannedMedia());

    assertThat(outcome.scannedStreamEnds()).isEmpty();
  }

  @Test
  @DisplayName("Should ignore every stream end when a probe version 8 result carries it")
  void shouldIgnoreEveryStreamEndWhenAProbeVersion8ResultCarriesIt() {
    var outcome = success(VERSION_8, scannedMedia().addStreamEnds(end(VIDEO, 14, 0)));

    assertThat(outcome.scannedStreamEnds()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("streamEndsOutOfContract")
  @DisplayName("Should reject the result when probe version 9 reports a stream end out of contract")
  void shouldRejectTheResultWhenProbeVersion9ReportsAStreamEndOutOfContract(
      String description, Consumer<ProbeMediaInfo.Builder> streamEnds) {
    var media = scannedMedia();
    streamEnds.accept(media);
    var report = report(VERSION_9, media);

    assertThatThrownBy(() -> mapper.map(report))
        .isInstanceOf(ProbeContractViolationException.class);
  }

  static Stream<Arguments> streamEndsOutOfContract() {
    return Stream.of(
        arguments(
            "an end beside keyframes that a container index gave",
            (Consumer<ProbeMediaInfo.Builder>)
                media ->
                    media
                        .setKeyframes(
                            ProbeKeyframes.newBuilder()
                                .setPositions(
                                    positions()
                                        .setSource(
                                            KeyframePositionSource
                                                .KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX)))
                        .addStreamEnds(end(VIDEO, 14, 0))),
        arguments(
            "an end beside a request for a packet scan",
            (Consumer<ProbeMediaInfo.Builder>)
                media ->
                    media
                        .setKeyframes(
                            ProbeKeyframes.newBuilder()
                                .setPacketScanRequired(
                                    KeyframePacketScanReason
                                        .KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX))
                        .addStreamEnds(end(VIDEO, 14, 0))),
        violation("two ends for one stream", end(AUDIO, 14, 0), end(AUDIO, 15, 0)),
        violation("an end for a video stream that is not the chosen one", end(SECOND_VIDEO, 14, 0)),
        violation("an end for a subtitle stream", end(SUBTITLE, 14, 0)),
        violation("an end for a stream that the result does not list", end(9, 14, 0)),
        violation("an end without a stream index", end(AUDIO, 14, 0).clearStreamIndex()),
        violation("an end without a time", SourceStreamEnd.newBuilder().setStreamIndex(AUDIO)),
        violation("an end of zero", end(AUDIO, 0, 0)),
        violation("a negative end", end(AUDIO, -1, 0)),
        violation("an end with negative nanoseconds", end(AUDIO, 14, -1)),
        violation("an end with a whole second of nanoseconds", end(AUDIO, 14, 1_000_000_000)),
        violation("an end beyond a protobuf duration", end(AUDIO, 315_576_000_001L, 0)),
        violation(
            "a chosen video stream that ends a nanosecond before its last keyframe presents",
            end(VIDEO, 1, 999_999_999)));
  }

  private static Arguments violation(String description, SourceStreamEnd.Builder... ends) {
    Consumer<ProbeMediaInfo.Builder> streamEnds =
        media -> Stream.of(ends).forEach(media::addStreamEnds);
    return arguments(description, streamEnds);
  }

  private static SourceStreamEnd.Builder end(int streamIndex, long seconds, int nanos) {
    var end = SourceStreamEnd.newBuilder().setStreamIndex(streamIndex);
    end.getEndTimeBuilder().setSeconds(seconds).setNanos(nanos);
    return end;
  }

  // A packet scan's result: the chosen video stream at index 0 with keyframes at 0 and 2 s, two
  // audio streams, a second video stream and a subtitle stream.
  private static ProbeMediaInfo.Builder scannedMedia() {
    return WorkerProbeResultFixture.version4MediaBuilder()
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(AUDIO).setCodecType("audio"))
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(COMMENTARY).setCodecType("audio"))
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(SECOND_VIDEO).setCodecType("video"))
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(SUBTITLE).setCodecType("subtitle"))
        .setKeyframes(ProbeKeyframes.newBuilder().setPositions(positions()));
  }

  private static KeyframePositions.Builder positions() {
    return KeyframePositions.newBuilder()
        .setStreamIndex(VIDEO)
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(90_000)
        .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
        .setKeyframeCount(2);
  }

  private ProbeOutcome.Success success(int version, ProbeMediaInfo.Builder media) {
    return (ProbeOutcome.Success) mapper.map(report(version, media));
  }

  private static ProbeAttemptReport report(int version, ProbeMediaInfo.Builder media) {
    return ProbeAttemptReport.newBuilder()
        .setResult(ProbeAttemptResult.newBuilder().setProbeVersion(version).setMedia(media))
        .addAllKeyframePresentationTimeDeltas(List.of(0L, 180_000L))
        .addAllKeyframeDecodeTimeDeltas(List.of(0L, 180_000L))
        .build();
  }
}
