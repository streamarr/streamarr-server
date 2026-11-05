package com.streamarr.server.services.serversettings;

import java.time.Duration;
import java.util.UUID;
import lombok.NonNull;

/**
 * How long the server keeps a stream session, and its segments, after the last request for it, with
 * the range a ServerAdmin can choose from. The id is the server settings row's, so every read and
 * change names the same setting.
 */
public record IdleSegmentRetentionSetting(@NonNull UUID id, int minutes) {

  public static final int MINIMUM_MINUTES = 5;
  public static final int MAXIMUM_MINUTES = 10_080;

  public int minimumMinutes() {
    return MINIMUM_MINUTES;
  }

  public int maximumMinutes() {
    return MAXIMUM_MINUTES;
  }

  Duration duration() {
    return Duration.ofMinutes(minutes);
  }

  static boolean allows(int minutes) {
    return minutes >= MINIMUM_MINUTES && minutes <= MAXIMUM_MINUTES;
  }
}
