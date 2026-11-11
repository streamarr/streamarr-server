package com.streamarr.server.domain.notification;

/**
 * What a family of kinds is about (ADR 0063 §What a Notification is, and is not). Each kind names
 * one, and the Notifications about each are read with their subjects and guarded by that subject's
 * read rule. A read that handles Notifications switches over every subject, so a new one cannot be
 * left out of the list, the count, the marks, live changes or retention.
 */
public enum NotificationSubject {
  /** One Profile share, from the pending offer to the ended share. */
  PROFILE_SHARE
}
