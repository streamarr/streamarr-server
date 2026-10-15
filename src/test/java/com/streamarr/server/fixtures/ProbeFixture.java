package com.streamarr.server.fixtures;

import com.streamarr.server.domain.streaming.KeyframeKnowledge;
import com.streamarr.server.domain.streaming.KeyframePacketScanReason;
import com.streamarr.server.domain.streaming.KeyframePosition;
import com.streamarr.server.domain.streaming.KeyframePositionSource;
import com.streamarr.server.domain.streaming.KeyframePositions;
import com.streamarr.server.domain.streaming.KeyframesUnavailableReason;
import com.streamarr.server.domain.streaming.MediaProbe;
import com.streamarr.server.domain.streaming.ProbeContainer;
import com.streamarr.server.domain.streaming.ProbeOutcome;
import com.streamarr.server.domain.streaming.StreamInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ProbeFixture {

  public static ProbeOutcome.Success completeProbe(MediaProbe probe) {
    return new ProbeOutcome.Success(
        ProbeContainer.builder()
            .format(probe.containerFormat())
            .duration(Optional.of(probe.duration()))
            .bitrate(OptionalLong.of(probe.bitrate()))
            .build(),
        streams(probe),
        probe.keyframes());
  }

  /**
   * Keyframes from zero through the media duration, one every {@code spacing}, in a millisecond
   * time base.
   */
  public static KeyframeKnowledge keyframesEvery(Duration spacing, Duration mediaDuration) {
    var keyframes =
        LongStream.iterate(
                0, time -> time <= mediaDuration.toMillis(), time -> time + spacing.toMillis())
            .mapToObj(time -> new KeyframePosition(time, time))
            .toList();
    return new KeyframeKnowledge.Known(
        KeyframePositions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1000)
            .source(KeyframePositionSource.CONTAINER_INDEX)
            .keyframes(keyframes)
            .build());
  }

  /** One value of each keyframe knowledge that does not hold the source's keyframe positions. */
  public static Stream<KeyframeKnowledge> keyframesThatAreNotKnown() {
    return Stream.of(
        new KeyframeKnowledge.NotReported(),
        new KeyframeKnowledge.PacketScanRequired(KeyframePacketScanReason.NO_CONTAINER_INDEX),
        new KeyframeKnowledge.Unavailable(KeyframesUnavailableReason.KEYFRAME_WITHOUT_TIMESTAMP));
  }

  private static List<StreamInfo> streams(MediaProbe probe) {
    if (!probe.streams().isEmpty()) {
      return probe.streams();
    }

    var streams = new ArrayList<StreamInfo>();
    streams.add(
        StreamInfo.builder()
            .codecType("video")
            .codec(Optional.ofNullable(probe.videoCodec()))
            .width(OptionalInt.of(probe.width()))
            .height(OptionalInt.of(probe.height()))
            .framerate(probe.framerate())
            .build());
    if (probe.audioCodec() == null) {
      return streams;
    }

    streams.add(
        StreamInfo.builder()
            .index(1)
            .codecType("audio")
            .codec(Optional.of(probe.audioCodec()))
            .channels(probe.audioChannels())
            .bitrate(probe.audioBitrate())
            .build());
    return streams;
  }
}
