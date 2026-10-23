package com.streamarr.server.services.streaming.remote;

import com.streamarr.transcode.v1.BackgroundProbeCapacityControl;
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
 * Reports each connected worker's probe capacities, the probes running against them and how the
 * worker sets its background probe capacity. A worker session's meters replace those of the session
 * it replaces, and leave when that session leaves.
 */
@RequiredArgsConstructor
final class WorkerProbeMeters {

  private static final String CAPACITY_METRIC = "streamarr.probes.capacity";
  private static final String RUNNING_METRIC = "streamarr.probes.running";
  private static final String CONTROL_METRIC = "streamarr.probes.background_capacity_control";
  private static final String CONTROL_PREFIX = "BACKGROUND_PROBE_CAPACITY_CONTROL_";

  /** One worker session's probe capacities and running probes. */
  interface Session {

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
          Tags.of("worker", workerId.toString(), "kind", kind.name().toLowerCase(Locale.ROOT));
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
        Gauge.builder(CONTROL_METRIC, () -> 1)
            .description("The way that the worker sets its background probe capacity")
            .tag("worker", workerId.toString())
            .tag("control", controlTag(session.backgroundProbeCapacityControl()))
            .register(meterRegistry));
    registered.put(workerId, new Registered(session, meters));
  }

  /** Removes the worker's meters when they still belong to the session that left. */
  synchronized void disconnected(@NonNull UUID workerId, @NonNull Session session) {
    var current = registered.get(workerId);
    if (current == null || current.session() != session) {
      return;
    }

    removeMeters(workerId);
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

  private record Registered(Session session, List<Meter> meters) {}
}
