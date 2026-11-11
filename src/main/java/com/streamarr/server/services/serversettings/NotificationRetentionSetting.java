package com.streamarr.server.services.serversettings;

import java.time.Duration;
import java.util.UUID;
import lombok.NonNull;

/**
 * How long the server keeps Notification history, and the unread news that its guard hides, after
 * its latest event (ADR 0063), with the range a ServerAdmin can choose from. The id is the server
 * settings row's, so every read and change names the same setting.
 */
public record NotificationRetentionSetting(@NonNull UUID id, int days) {

  public static final int MINIMUM_DAYS = 1;
  public static final int MAXIMUM_DAYS = 365;

  public int minimumDays() {
    return MINIMUM_DAYS;
  }

  public int maximumDays() {
    return MAXIMUM_DAYS;
  }

  Duration duration() {
    return Duration.ofDays(days);
  }

  static boolean allows(int days) {
    return days >= MINIMUM_DAYS && days <= MAXIMUM_DAYS;
  }
}
