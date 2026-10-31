package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import java.util.List;
import lombok.NonNull;

/**
 * A worker's probe result with the keyframe positions and the keyframe interval bytes that the
 * worker sent before it. The server joins the worker's parts, so {@code keyframePositionsParts}
 * holds at most one part and {@code streamMeasurementParts} at most one part for each stream.
 */
public record ProbeAttemptReport(
    @NonNull ProbeAttemptResult result,
    @NonNull List<ProbeKeyframePositionsPart> keyframePositionsParts,
    @NonNull List<ProbeStreamMeasurementPart> streamMeasurementParts) {

  public ProbeAttemptReport {
    keyframePositionsParts = List.copyOf(keyframePositionsParts);
    streamMeasurementParts = List.copyOf(streamMeasurementParts);
  }

  /** A report without keyframe interval bytes. */
  public ProbeAttemptReport(
      @NonNull ProbeAttemptResult result,
      @NonNull List<ProbeKeyframePositionsPart> keyframePositionsParts) {
    this(result, keyframePositionsParts, List.of());
  }
}
