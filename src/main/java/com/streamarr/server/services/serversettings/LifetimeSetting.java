package com.streamarr.server.services.serversettings;

import java.time.Duration;
import java.util.UUID;
import lombok.NonNull;

/**
 * How long something the server issues stays usable after it is issued, the share code lifetime or
 * the invitation lifetime (ADR 0024), with the range a ServerAdmin can choose from. The id is the
 * server settings row's, so every read and change names the same setting.
 */
public record LifetimeSetting(@NonNull UUID id, int hours) {

  public static final int MINIMUM_HOURS = 1;
  public static final int MAXIMUM_HOURS = 720;

  public int minimumHours() {
    return MINIMUM_HOURS;
  }

  public int maximumHours() {
    return MAXIMUM_HOURS;
  }

  Duration duration() {
    return Duration.ofHours(hours);
  }

  static boolean allows(int hours) {
    return hours >= MINIMUM_HOURS && hours <= MAXIMUM_HOURS;
  }
}
