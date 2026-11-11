package com.streamarr.server.services.notification;

/** What the guard decided for a Notification's recipient (ADR 0063 §The guard). */
enum NotificationVisibility {
  /** The recipient may see the Notification's subject. */
  SHOWN,
  /** The recipient may no longer see the subject; the Notification reads as missing. */
  HIDDEN,
  /** No decision could be made; whatever acts on the Notification keeps it as it is. */
  UNDECIDED
}
