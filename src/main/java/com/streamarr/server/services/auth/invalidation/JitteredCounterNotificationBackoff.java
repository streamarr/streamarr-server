package com.streamarr.server.services.auth.invalidation;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
class JitteredCounterNotificationBackoff implements CounterNotificationBackoff {

  private final SecureRandom jitterSource = new SecureRandom();

  @Override
  public void sleep(long backoffMs) {
    try {
      Thread.sleep(backoffMs + jitterSource.nextLong(backoffMs / 2 + 1));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }
  }
}
