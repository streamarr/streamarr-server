package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import java.util.List;
import lombok.NonNull;

/** A worker's probe result with the keyframe position parts that the worker sent before it. */
public record ProbeAttemptReport(
    @NonNull ProbeAttemptResult result,
    @NonNull List<ProbeKeyframePositionsPart> keyframePositionsParts) {

  public ProbeAttemptReport {
    keyframePositionsParts = List.copyOf(keyframePositionsParts);
  }
}
