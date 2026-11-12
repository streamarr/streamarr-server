package com.streamarr.server.services.events.notification;

import java.util.Optional;

/**
 * What a recipient was told, as its kind's record type holds it (ADR 0063 §What a Notification
 * stores). It is written when the event happens, and nothing reads the subject to change it.
 */
public interface NotificationSnapshot {

  /**
   * The Notification's line in English, worded from what the recipient was told and, for a request,
   * from how it resolved, which is empty while it waits. The line is plain text, and every name in
   * it stands in quotation marks.
   */
  String line(Optional<RequestResolution> resolution);
}
