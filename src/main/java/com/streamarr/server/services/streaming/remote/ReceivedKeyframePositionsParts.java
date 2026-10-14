package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import java.util.ArrayList;
import java.util.List;

/** The keyframe positions parts that one pending probe attempt has received so far. */
final class ReceivedKeyframePositionsParts {

  // worker_session.proto bounds each list of a part to this many entries.
  private static final int MAXIMUM_ENTRIES_PER_PART = 4_096;
  // 4,194,304 keyframes: more than 48 hours of 24 fps video in which every frame is a keyframe.
  private static final int MAXIMUM_PARTS_PER_PROBE = 1_024;

  private final List<ProbeKeyframePositionsPart> parts = new ArrayList<>();
  private boolean exceedsContract;

  synchronized void add(ProbeKeyframePositionsPart part) {
    if (exceedsContract) {
      return;
    }

    if (parts.size() == MAXIMUM_PARTS_PER_PROBE
        || part.getPresentationTimeDeltasCount() > MAXIMUM_ENTRIES_PER_PART
        || part.getDecodeTimeDeltasCount() > MAXIMUM_ENTRIES_PER_PART) {
      exceedsContract = true;
      parts.clear();
      return;
    }

    parts.add(part);
  }

  synchronized boolean exceedsContract() {
    return exceedsContract;
  }

  synchronized List<ProbeKeyframePositionsPart> parts() {
    return List.copyOf(parts);
  }
}
