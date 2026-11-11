package com.streamarr.server.services.notification;

/**
 * Every expected reason {@code markNotificationsRead} refuses; each maps to one schema error type.
 */
public sealed interface MarkNotificationsReadRejection {

  /** The mark names fewer or more Notifications than one mark takes. */
  record IdCountOutOfRange(int minimum, int maximum) implements MarkNotificationsReadRejection {}
}
