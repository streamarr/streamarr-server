package com.streamarr.server.config.health;

import com.streamarr.server.services.streaming.TranscodeExecutor;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

/**
 * Reports worker connectivity, available transcode capacity, and each connected worker that has no
 * worker-verified encoder for a video codec family that the server encodes.
 */
@Component
@RequiredArgsConstructor
public class TranscodeExecutorHealthIndicator implements HealthIndicator {

  static final Status DEGRADED =
      new Status(
          "DEGRADED",
          "A connected worker has no worker-verified encoder for a video codec family that the"
              + " server encodes");

  private final TranscodeExecutor transcodeExecutor;

  @Override
  public Health health() {
    if (!transcodeExecutor.isHealthy()) {
      return Health.down().withDetail("reason", "No transcode capacity available").build();
    }

    var workersMissingEncoders = workersMissingEncoders();
    var health = Health.up();
    if (!workersMissingEncoders.isEmpty()) {
      health = Health.status(DEGRADED).withDetail("workersMissingEncoders", workersMissingEncoders);
    }

    var slots = transcodeExecutor.availableSlots();
    if (slots == TranscodeExecutor.UNBOUNDED_SLOTS) {
      return health.withDetail("capacity", "unbounded").build();
    }

    return health.withDetail("availableSlots", slots).build();
  }

  // Each worker's id with the codec families, in name order, that it has no encoder for.
  private Map<String, List<String>> workersMissingEncoders() {
    var missing = new TreeMap<String, List<String>>();
    for (var worker : transcodeExecutor.verifiedVideoCodecFamiliesByWorker()) {
      var families = worker.missingCodecFamilies();
      if (!families.isEmpty()) {
        missing.put(worker.workerId().toString(), families);
      }
    }

    return missing;
  }
}
