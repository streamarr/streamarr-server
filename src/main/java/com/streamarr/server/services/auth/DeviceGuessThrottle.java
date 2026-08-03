package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.DeviceAuthProperties;
import com.streamarr.server.exceptions.TooManyDeviceAttemptsException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * One shared guessing budget for lookup and decision, keyed by the calling account.
 *
 * <p>Shared on purpose: lookup is the enumeration oracle — a 200 carrying a device name versus a
 * 404 — so two independent budgets would hand an attacker twice the attempts against the same
 * secret. Keyed by account rather than source for the reason {@link LoginThrottle} records: behind
 * a reverse proxy every caller shares one address, and a per-source block would lock out the house.
 *
 * <p>Keys are bounded by the number of authenticated accounts, so unlike {@link LoginThrottle}
 * there is nothing to spray and no sweeper to run.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceGuessThrottle {

  private final DeviceAuthProperties properties;
  private final Clock clock;

  private final ConcurrentHashMap<UUID, Deque<Instant>> attempts = new ConcurrentHashMap<>();

  /** Reserves one attempt for this account, or throws with the wait until a slot frees. */
  public void registerAttempt(UUID accountId) {
    var waitUntilFree = new AtomicReference<Duration>();
    var reserved = new AtomicBoolean();

    attempts.compute(
        accountId,
        (_, timestamps) -> {
          var current = timestamps == null ? new ArrayDeque<Instant>() : timestamps;
          prune(current);

          if (current.size() < properties.maxGuessAttempts()) {
            current.addLast(clock.instant());
            reserved.set(true);
            return current;
          }

          waitUntilFree.set(remainingWindow(current.peekFirst()));
          return current;
        });

    if (reserved.get()) {
      return;
    }

    log.warn("Device pairing lookup throttled: guess budget exhausted for account {}", accountId);
    throw new TooManyDeviceAttemptsException(waitUntilFree.get());
  }

  private Duration remainingWindow(Instant oldestAttempt) {
    var freesAt = oldestAttempt.plus(properties.guessWindow());
    var remaining = Duration.between(clock.instant(), freesAt);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  private void prune(Deque<Instant> timestamps) {
    var cutoff = clock.instant().minus(properties.guessWindow());
    while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
      timestamps.pollFirst();
    }
  }
}
