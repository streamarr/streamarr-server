package com.streamarr.server.services.library;

/**
 * How one keyframe packet scan execution ends: it is done, or it waits for a free worker slot or a
 * free scan slot without counting as a failure.
 */
public sealed interface KeyframePacketScanResult {

  record Completed() implements KeyframePacketScanResult {}

  record Deferred() implements KeyframePacketScanResult {}
}
