package com.streamarr.server.services.auth.invalidation;

import com.streamarr.server.services.auth.CounterKind;
import java.util.Optional;

/**
 * The wire contract for cross-instance counter notifications: {@code KIND|key|version} published on
 * {@link #CHANNEL}; membership keys keep their inner colon. Encode and parse live together so
 * publishers and the listener cannot drift.
 */
public record CounterNotificationPayload(CounterKind kind, String key, long version) {

  public static final String CHANNEL = "streamarr_counters";

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
    if (parts[1].isEmpty()) {
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
