package com.streamarr.server.services.serversettings;

/**
 * Every expected reason {@code changeNotificationRetention} refuses; each maps to one schema error
 * type.
 */
public sealed interface ChangeNotificationRetentionRejection {

  record OutOfRange() implements ChangeNotificationRetentionRejection {}
}
