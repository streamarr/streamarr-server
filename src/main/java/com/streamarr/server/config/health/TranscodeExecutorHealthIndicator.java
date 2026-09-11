package com.streamarr.server.config.health;

import com.streamarr.server.services.streaming.TranscodeExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Reports worker connectivity and available transcode capacity. */
@Component
@RequiredArgsConstructor
public class TranscodeExecutorHealthIndicator implements HealthIndicator {

  private final TranscodeExecutor transcodeExecutor;

  @Override
  public Health health() {
    if (!transcodeExecutor.isHealthy()) {
      return Health.down().withDetail("reason", "No transcode capacity available").build();
    }
    var slots = transcodeExecutor.availableSlots();
    if (slots == TranscodeExecutor.UNBOUNDED_SLOTS) {
      return Health.up().withDetail("capacity", "unbounded").build();
    }
    return Health.up().withDetail("availableSlots", slots).build();
  }
}
