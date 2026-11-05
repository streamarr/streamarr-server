package com.streamarr.server.services.serversettings;

/**
 * Every expected reason {@code changeIdleSegmentRetention} refuses; each maps to one schema error
 * type.
 */
public sealed interface ChangeIdleSegmentRetentionRejection {

  record OutOfRange() implements ChangeIdleSegmentRetentionRejection {}
}
