package com.streamarr.server.domain.streaming;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * One chunk of a file's bitrate scan (ADR 0054): consecutive keyframes of the chosen video stream,
 * each of which opens one keyframe interval. {@code end} holds the next chunk's first keyframe,
 * where the chunk's last interval ends, and is empty for the last chunk, which runs to the end of
 * the file. Times count ticks of {@code timeBaseNumerator / timeBaseDenominator} seconds on the
 * timeline of the file's stored keyframe positions.
 */
@Builder
public record BitrateScanChunkRequest(
    @NonNull Path sourcePath,
    @NonNull UUID attemptId,
    int measurementVersion,
    int chosenVideoStreamIndex,
    @NonNull List<Integer> measuredStreamIndexes,
    int timeBaseNumerator,
    int timeBaseDenominator,
    @NonNull List<KeyframePosition> keyframes,
    @NonNull Optional<KeyframePosition> end,
    boolean firstChunk) {

  public BitrateScanChunkRequest {
    measuredStreamIndexes = List.copyOf(measuredStreamIndexes);
    keyframes = List.copyOf(keyframes);
  }
}
