package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import java.util.List;
import lombok.NonNull;

/**
 * A worker's probe result with the keyframe positions that the worker sent before it. The server
 * joins the worker's parts, so the list holds at most one part.
 */
public record ProbeAttemptReport(
    @NonNull ProbeAttemptResult result,
    @NonNull List<ProbeKeyframePositionsPart> keyframePositionsParts) {

  public ProbeAttemptReport {
    keyframePositionsParts = List.copyOf(keyframePositionsParts);
  }
}
