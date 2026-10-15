package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import java.util.List;

/**
 * The keyframe positions that one pending probe attempt has received so far. The parts are joined
 * into one part in the order they arrived, so many small parts cost no more than one large part.
 */
final class ReceivedKeyframePositionsParts {

  // worker_session.proto bounds each list of a part to this many entries.
  private static final int MAXIMUM_ENTRIES_PER_PART = 4_096;
  // worker_session.proto bounds the entries across the parts of one attempt to this many.
  private static final int MAXIMUM_ENTRIES_PER_PROBE = 1_048_576;

  private final ProbeKeyframePositionsPart.Builder received =
      ProbeKeyframePositionsPart.newBuilder();
  private boolean violatesContract;

  synchronized void add(ProbeKeyframePositionsPart part) {
    if (violatesContract) {
      return;
    }

    var entries = part.getDecodeTimeDeltasCount();
    if (part.getPresentationTimeDeltasCount() != entries
        || entries > MAXIMUM_ENTRIES_PER_PART
        || received.getDecodeTimeDeltasCount() + entries > MAXIMUM_ENTRIES_PER_PROBE) {
      violatesContract = true;
      received.clear();
      return;
    }

    received
        .setProbeAttemptId(part.getProbeAttemptId())
        .addAllPresentationTimeDeltas(part.getPresentationTimeDeltasList())
        .addAllDecodeTimeDeltas(part.getDecodeTimeDeltasList());
  }

  synchronized boolean violatesContract() {
    return violatesContract;
  }

  synchronized List<ProbeKeyframePositionsPart> parts() {
    if (received.getDecodeTimeDeltasCount() == 0) {
      return List.of();
    }

    return List.of(received.build());
  }
}
