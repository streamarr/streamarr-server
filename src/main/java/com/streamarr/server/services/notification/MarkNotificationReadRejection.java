package com.streamarr.server.services.notification;

/**
 * Every expected reason {@code markNotificationRead} refuses; each maps to one schema error type.
 */
public sealed interface MarkNotificationReadRejection {

  /** No Notification of the Account that the guard shows it has the ID. */
  record NotFound() implements MarkNotificationReadRejection {}
}
