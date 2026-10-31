package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The keyframe interval bytes that one pending probe attempt has received so far. Each stream's
 * parts are joined into one part in the order they arrived.
 */
final class ReceivedStreamMeasurementParts {

  // worker_session.proto bounds each part to this many entries.
  private static final int MAXIMUM_ENTRIES_PER_PART = 4_096;
  // worker_session.proto bounds the entries across the parts of one attempt to this many.
  private static final int MAXIMUM_ENTRIES_PER_PROBE = 8_388_608;
  // worker_session.proto bounds the streams that the parts of one attempt name to this many.
  private static final int MAXIMUM_STREAMS_PER_PROBE = 1_024;

  private final Map<Integer, ProbeStreamMeasurementPart.Builder> received = new LinkedHashMap<>();
  private int entries;
  private boolean violatesContract;

  synchronized void add(ProbeStreamMeasurementPart part) {
    if (violatesContract) {
      return;
    }

    var partEntries = part.getKeyframeIntervalBytesCount();
    var namesNewStream = !received.containsKey(part.getStreamIndex());
    if (partEntries == 0
        || partEntries > MAXIMUM_ENTRIES_PER_PART
        || entries + partEntries > MAXIMUM_ENTRIES_PER_PROBE
        || (namesNewStream && received.size() == MAXIMUM_STREAMS_PER_PROBE)) {
      violatesContract = true;
      received.clear();
      return;
    }

    entries += partEntries;
    received
        .computeIfAbsent(
            part.getStreamIndex(),
            streamIndex ->
                ProbeStreamMeasurementPart.newBuilder()
                    .setProbeAttemptId(part.getProbeAttemptId())
                    .setStreamIndex(streamIndex))
        .addAllKeyframeIntervalBytes(part.getKeyframeIntervalBytesList());
  }

  synchronized boolean violatesContract() {
    return violatesContract;
  }

  /** One part for each stream that the worker sent parts for, in the order the streams arrived. */
  synchronized List<ProbeStreamMeasurementPart> parts() {
    return received.values().stream().map(ProbeStreamMeasurementPart.Builder::build).toList();
  }
}
