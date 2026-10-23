package com.streamarr.server.services.streaming.remote;

import com.google.protobuf.Duration;
import com.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import com.streamarr.transcode.v1.SegmentUploadMetadata;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reports each connected worker's playback slots and probe capacities, the work running against
 * them, how the worker sets its background probe capacity, and how fast it produces media segments.
 * A worker session's meters replace those of the session it replaces, and leave when that session
 * leaves.
 */
@RequiredArgsConstructor
final class WorkerCapacityMeters {

  private static final String WORKER_TAG = "worker";
  private static final String CAPACITY_METRIC = "streamarr.probes.capacity";
  private static final String RUNNING_METRIC = "streamarr.probes.running";
  private static final String CONTROL_METRIC = "streamarr.probes.background_capacity_control";
  private static final String CONTROL_PREFIX = "BACKGROUND_PROBE_CAPACITY_CONTROL_";
  private static final String PLAYBACK_SLOTS_METRIC = "streamarr.streaming.playback_slots";
  private static final String PLAYBACK_SLOTS_IN_USE_METRIC =
      "streamarr.streaming.playback_slots_in_use";
  private static final String PRODUCTION_SPEED_METRIC = "streamarr.streaming.production_speed";

  /** One worker session's playback slots, probe capacities and the work running against them. */
  interface Session {

    int playbackSlots();

    int playbackSlotsInUse();

    int grantedUnits(ProbeKind kind);

    int runningProbes(ProbeKind kind);

    BackgroundProbeCapacityControl backgroundProbeCapacityControl();
  }

  private final MeterRegistry meterRegistry;

  // Guarded by this monitor.
  private final Map<UUID, Registered> registered = new HashMap<>();

  synchronized void connected(@NonNull UUID workerId, @NonNull Session session) {
    removeMeters(workerId);
    var meters = new ArrayList<Meter>();
    for (var kind : ProbeKind.values()) {
      var tags =
          Tags.of(WORKER_TAG, workerId.toString(), "kind", kind.name().toLowerCase(Locale.ROOT));
      meters.add(
          Gauge.builder(CAPACITY_METRIC, session, reported -> reported.grantedUnits(kind))
              .description("The units of a probe capacity that the worker grants")
              .tags(tags)
              .strongReference(true)
              .register(meterRegistry));
      meters.add(
          Gauge.builder(RUNNING_METRIC, session, reported -> reported.runningProbes(kind))
              .description("The probes of a kind running on the worker")
              .tags(tags)
              .strongReference(true)
              .register(meterRegistry));
    }

    meters.add(
        Gauge.builder(PLAYBACK_SLOTS_METRIC, session, Session::playbackSlots)
            .description("The job attempts that the worker runs at once")
            .tag(WORKER_TAG, workerId.toString())
            .strongReference(true)
            .register(meterRegistry));
    meters.add(
        Gauge.builder(PLAYBACK_SLOTS_IN_USE_METRIC, session, Session::playbackSlotsInUse)
            .description("The job attempts that run on the worker")
            .tag(WORKER_TAG, workerId.toString())
            .strongReference(true)
            .register(meterRegistry));
    meters.add(
        Gauge.builder(CONTROL_METRIC, () -> 1)
            .description("The way that the worker sets its background probe capacity")
            .tag(WORKER_TAG, workerId.toString())
            .tag("control", controlTag(session.backgroundProbeCapacityControl()))
            .register(meterRegistry));
    var productionSpeed =
        DistributionSummary.builder(PRODUCTION_SPEED_METRIC)
            .description("Media seconds that the worker produced per second of production time")
            .tag(WORKER_TAG, workerId.toString())
            .register(meterRegistry);
    meters.add(productionSpeed);
    registered.put(workerId, new Registered(session, meters, productionSpeed));
  }

  /** Removes the worker's meters when they still belong to the session that left. */
  synchronized void disconnected(@NonNull UUID workerId, @NonNull Session session) {
    var current = registered.get(workerId);
    if (current == null || current.session() != session) {
      return;
    }

    removeMeters(workerId);
  }

  /**
   * Reports the media segment's production speed: the media seconds that the worker produced in
   * each second of production time. An initialization segment carries no timing, and a segment
   * whose session no longer owns the worker's meters is not reported.
   */
  synchronized void segmentPublished(
      @NonNull UUID workerId, @NonNull Session session, @NonNull SegmentUploadMetadata metadata) {
    var current = registered.get(workerId);
    if (current == null || current.session() != session) {
      return;
    }

    if (!metadata.hasMediaDuration() || !metadata.hasProductionTime()) {
      return;
    }

    var productionSeconds = seconds(metadata.getProductionTime());
    if (productionSeconds <= 0) {
      return;
    }

    current.productionSpeed().record(seconds(metadata.getMediaDuration()) / productionSeconds);
  }

  private static double seconds(Duration duration) {
    return duration.getSeconds() + duration.getNanos() / 1_000_000_000.0;
  }

  // Guarded by this monitor.
  private void removeMeters(UUID workerId) {
    var previous = registered.remove(workerId);
    if (previous == null) {
      return;
    }

    previous.meters().forEach(meterRegistry::remove);
  }

  private static String controlTag(BackgroundProbeCapacityControl control) {
    return control.name().replace(CONTROL_PREFIX, "").toLowerCase(Locale.ROOT);
  }

  private record Registered(
      Session session, List<Meter> meters, DistributionSummary productionSpeed) {}
}
