package com.streamarr.server.services.auth.invalidation;

import com.streamarr.server.services.auth.CounterKind;
import java.util.Optional;

/** Payload shape: {@code KIND|key|version}; membership keys keep their inner colon. */
public record CounterNotificationPayload(CounterKind kind, String key, long version) {

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
