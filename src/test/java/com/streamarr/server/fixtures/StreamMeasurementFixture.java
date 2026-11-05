package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.KeyframeIntervalCounts;
import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.MeasuredStream;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import com.streamarr.server.domain.streaming.StreamMeasurementSource;
import com.streamarr.server.domain.streaming.StreamMeasurements;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.experimental.UtilityClass;

/**
 * A video stream and an audio stream with two keyframe intervals, and their measurement. The video
 * stream's second interval holds the largest count that one interval can hold. {@link CopySource}
 * builds a copied source of any keyframe intervals.
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

  /** {@code count} keyframes, one at each whole second from zero, in milliseconds. */
  public static List<Long> everySecond(int count) {
    return LongStream.range(0, count).map(second -> second * 1_000).boxed().toList();
  }

  /** {@code count} keyframe intervals that each hold {@code value}. */
  public static List<Long> repeat(int count, long value) {
    return Collections.nCopies(count, value);
  }

  /** The keyframe intervals of each part, one part after another. */
  @SafeVarargs
  public static List<Long> concat(List<Long>... parts) {
    return Stream.of(parts).flatMap(List::stream).toList();
  }

  /**
   * A copied source whose keyframes count milliseconds and whose keyframe interval {@code i} holds
   * {@code videoBytes[i]} in 24 video samples. When {@code audioBytes} is set, the interval also
   * holds {@code audioBytes[i]} AC-3 bytes in {@code audioPackets[i]} frames of 1,536 samples at 48
   * kHz, 32 ms each.
   */
  @Builder
  public record CopySource(
      Duration duration,
      List<Long> keyframeMillis,
      List<Long> videoBytes,
      List<Long> audioBytes,
      List<Long> audioPackets) {

    private static final long VIDEO_SAMPLES_PER_INTERVAL = 24;
    private static final long AC3_FRAME_SAMPLES = 1_536;

    public MediaProbe probe() {
      return probeFrom(StreamSessionFixture.defaultProbeBuilder());
    }

    /** This source's duration, streams, keyframes and measurement on the given probe. */
    public MediaProbe probeFrom(MediaProbe.MediaProbeBuilder base) {
      var probe = base.duration(duration);
      var video = ProbeFixture.videoStreamBuilder(probe.build()).build();
      var streams = new ArrayList<StreamInfo>(List.of(video));
      var measured = new ArrayList<MeasuredStream>(List.of(measuredVideo(video.index())));
      if (audioBytes != null) {
        var audio = ProbeFixture.audioStreamBuilder("ac3").index(video.index() + 1).build();
        streams.add(audio);
        measured.add(measuredAudio(audio.index()));
      }

      return probe
          .keyframes(new KeyframeKnowledge.Known(positions(video.index())))
          .streams(streams)
          .streamMeasurements(
              Optional.of(new StreamMeasurements(StreamMeasurementSource.SAMPLE_TABLE, measured)))
          .build();
    }

    private KeyframePositions positions(int streamIndex) {
      return KeyframePositions.builder()
          .streamIndex(streamIndex)
          .timeBaseNumerator(1)
          .timeBaseDenominator(1_000)
          .source(KeyframePositionSource.CONTAINER_INDEX)
          .keyframes(keyframeMillis.stream().map(time -> new KeyframePosition(time, time)).toList())
          .build();
    }

    private MeasuredStream measuredVideo(int streamIndex) {
      var samples = VIDEO_SAMPLES_PER_INTERVAL * videoBytes.size();
      return MeasuredStream.builder()
          .streamIndex(streamIndex)
          .measuredBytes(sum(videoBytes))
          .packetCount(samples)
          .timeBaseNumerator(1)
          .timeBaseDenominator(24)
          .measuredDuration(samples)
          .keyframeIntervalBytes(counts(videoBytes))
          .keyframeIntervalPacketCounts(
              counts(Collections.nCopies(videoBytes.size(), VIDEO_SAMPLES_PER_INTERVAL)))
          .build();
    }

    private MeasuredStream measuredAudio(int streamIndex) {
      var frames = sum(audioPackets);
      return MeasuredStream.builder()
          .streamIndex(streamIndex)
          .measuredBytes(sum(audioBytes))
          .packetCount(frames)
          .timeBaseNumerator(1)
          .timeBaseDenominator(48_000)
          .measuredDuration(frames * AC3_FRAME_SAMPLES)
          .keyframeIntervalBytes(counts(audioBytes))
          .keyframeIntervalPacketCounts(counts(audioPackets))
          .build();
    }

    private static long sum(List<Long> values) {
      return values.stream().mapToLong(Long::longValue).sum();
    }

    private static KeyframeIntervalCounts counts(List<Long> values) {
      return KeyframeIntervalCounts.of(values.stream().mapToLong(Long::longValue).toArray());
    }
  }
}
