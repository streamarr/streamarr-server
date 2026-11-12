package com.streamarr.server.services.events.notification;

/**
 * Whether a Notification waits for a decision on its subject or only tells (ADR 0063 §Requests and
 * news). A kind has one form, and the form never changes.
 */
public enum NotificationForm {
  /** Waits for a decision on its subject, and stores the state its subject's events write. */
  REQUEST,
  /** Needs no decision, and stays unread until its recipient marks it read. */
  NEWS
}
