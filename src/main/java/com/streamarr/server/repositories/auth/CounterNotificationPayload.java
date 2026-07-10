package com.streamarr.server.repositories.auth;

import com.streamarr.server.domain.auth.CounterKind;
import java.util.Optional;

/**
 * The wire contract for cross-instance counter notifications: {@code KIND|key|version} published on
 * {@link #CHANNEL}; membership keys keep their inner colon. Encode and parse live together so
 * publishers and the listener cannot drift.
 */
public record CounterNotificationPayload(CounterKind kind, String key, long version) {

  public static final String CHANNEL = "streamarr_counters";

  public CounterNotificationPayload {
    if (kind == null) {
      throw new IllegalArgumentException("Counter notification kind must be present.");
    }
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("Counter notification key must not be blank.");
    }
    if (key.contains("|")) {
      throw new IllegalArgumentException(
          "Counter notification key must not contain the '|' delimiter.");
    }
    if (version < 0) {
      throw new IllegalArgumentException("Counter notification version must not be negative.");
    }
  }

  public String encode() {
    return kind.name() + "|" + key + "|" + version;
  }

  public static Optional<CounterNotificationPayload> parse(String payload) {
    if (payload == null) {
      return Optional.empty();
    }

    var parts = payload.split("\\|", 3);
    if (parts.length != 3) {
      return Optional.empty();
    }

    try {
      return Optional.of(
          new CounterNotificationPayload(
              CounterKind.valueOf(parts[0]), parts[1], Long.parseLong(parts[2])));
    } catch (IllegalArgumentException _) {
      return Optional.empty();
    }
  }
}
