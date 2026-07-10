package com.streamarr.server.config.health;

import com.streamarr.server.services.auth.invalidation.CounterNotificationListener;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * DOWN whenever the counter notification feed is not actively subscribed: revocations from other
 * instances are invisible and caches serve read-through until the listener reconnects.
 */
@Component
@RequiredArgsConstructor
public class CounterNotificationHealthIndicator implements HealthIndicator {

  private final CounterNotificationListener listener;

  @Override
  public Health health() {
    if (!listener.isListening()) {
      return Health.down()
          .withDetail("reason", "Counter notification feed disconnected")
          .withDetail("consecutiveFailures", listener.consecutiveConnectionFailures())
          .build();
    }
    return Health.up().build();
  }
}
