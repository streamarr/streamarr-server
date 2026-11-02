package com.streamarr.server.domain.task;

import com.streamarr.server.domain.streaming.KeyframePositions;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * What a bitrate scan of a file reads from the file's stored outcome: the key its chunks follow,
 * the keyframe positions, the container's duration when the probe reported one, every audio stream,
 * and whether the chosen video stream has a probed bitrate.
 */
@Builder
public record BitrateScanInputs(
    @NonNull BitrateScanKey key,
    @NonNull KeyframePositions positions,
    @NonNull Optional<Duration> containerDuration,
    @NonNull List<Integer> audioStreamIndexes,
    boolean chosenVideoHasProbedBitrate) {

  public BitrateScanInputs {
    audioStreamIndexes = List.copyOf(audioStreamIndexes);
  }
}
