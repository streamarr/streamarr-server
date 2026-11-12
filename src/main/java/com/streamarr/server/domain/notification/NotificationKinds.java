package com.streamarr.server.domain.notification;

import java.util.Optional;

/**
 * The kinds a stored Notification is read back by, through their stored names. A kind keeps its
 * name for good, so a stored Notification always finds its kind.
 */
public interface NotificationKinds {

  Optional<NotificationKind> kindNamed(String name);
}
