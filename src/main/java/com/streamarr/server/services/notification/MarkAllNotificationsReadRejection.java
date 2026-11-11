package com.streamarr.server.services.notification;

/**
 * Every expected reason {@code markAllNotificationsRead} refuses; each maps to one schema error
 * type.
 */
public sealed interface MarkAllNotificationsReadRejection {

  /** The client's position could not be read as a position in the Account's list. */
  record UnreadablePosition() implements MarkAllNotificationsReadRejection {}
}
