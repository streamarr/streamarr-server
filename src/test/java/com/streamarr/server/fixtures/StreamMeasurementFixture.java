package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * A video stream and an audio stream with two keyframe intervals, and their measurement. The video
 * stream's second interval holds the largest count that one interval can hold.
 */
@UtilityClass
public class StreamMeasurementFixture {

  public static final int VIDEO = 0;
  public static final int AUDIO = 1;

  /** Two keyframes on a 90 kHz time base, the second at this many ticks. */
  public static KeyframePositions positions(long secondKeyframeTicks) {
    return positions(secondKeyframeTicks, KeyframePositionSource.CONTAINER_INDEX);
  }

  public static KeyframePositions positions(
      long secondKeyframeTicks, KeyframePositionSource source) {
    return KeyframePositions.builder()
        .streamIndex(VIDEO)
        .timeBaseNumerator(1)
        .timeBaseDenominator(90_000)
        .source(source)
        .keyframes(
            List.of(
                new KeyframePosition(0, 0),
                new KeyframePosition(secondKeyframeTicks, secondKeyframeTicks)))
        .build();
  }

  public static ProbeOutcome.Success measured(KeyframePositions positions) {
    return success(positions, Optional.of(measurement()));
  }

  public static ProbeOutcome.Success unmeasured(KeyframePositions positions) {
    return success(positions, Optional.empty());
  }

  public static ProbeOutcome.Success success(
      KeyframePositions positions, Optional<StreamMeasurements> measurements) {
    return successBuilder()
        .keyframes(new KeyframeKnowledge.Known(positions))
        .streamMeasurements(measurements)
        .build();
  }

  /** The video stream and the audio stream, with no keyframe knowledge and no measurement yet. */
  public static ProbeOutcome.Success.SuccessBuilder successBuilder() {
    return ProbeOutcome.Success.builder()
        .container(ProbeContainer.builder().build())
        .streams(
            List.of(
                StreamInfo.builder().index(VIDEO).codecType("video").build(),
                StreamInfo.builder().index(AUDIO).codecType("audio").build()));
  }

  public static StreamMeasurements measurement() {
    return new StreamMeasurements(StreamMeasurementSource.SAMPLE_TABLE, List.of(video(), audio()));
  }

  public static MeasuredStream video() {
    return MeasuredStream.builder()
        .streamIndex(VIDEO)
        .measuredBytes(700L + 0xFFFF_FFFFL)
        .packetCount(48)
        .timeBaseNumerator(1)
        .timeBaseDenominator(12_288)
        .measuredDuration(24_576)
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(700, 0xFFFF_FFFFL))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(12, 36))
        .build();
  }

  public static MeasuredStream audio() {
    return MeasuredStream.builder()
        .streamIndex(AUDIO)
        .measuredBytes(100)
        .packetCount(94)
        .timeBaseNumerator(1)
        .timeBaseDenominator(48_000)
        .measuredDuration(96_000)
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(40, 60))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(46, 48))
        .build();
  }

  /**
   * A stream that holds {@code measuredBytes} in one keyframe interval, on a millisecond time base.
   * The caller sets its measured duration.
   */
  public static MeasuredStream.MeasuredStreamBuilder measuredStreamBuilder(
      int streamIndex, long measuredBytes) {
    return MeasuredStream.builder()
        .streamIndex(streamIndex)
        .measuredBytes(measuredBytes)
        .packetCount(1)
        .timeBaseNumerator(1)
        .timeBaseDenominator(1_000)
        .keyframeIntervalBytes(KeyframeIntervalCounts.of(measuredBytes))
        .keyframeIntervalPacketCounts(KeyframeIntervalCounts.of(1));
  }
}
