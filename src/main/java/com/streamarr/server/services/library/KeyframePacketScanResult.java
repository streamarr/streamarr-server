package com.streamarr.server.services.library;

/**
 * How one keyframe packet scan execution ends: it is done, it waits for free probe capacity without
 * counting as a failure, or the scan ran without a usable result. A scan without a usable result
 * timed out, got a worker reply that breaks the worker contract or reported no keyframe result, so
 * it held a unit of probe capacity for nothing.
 */
public sealed interface KeyframePacketScanResult {

  record Completed() implements KeyframePacketScanResult {}

  record Deferred() implements KeyframePacketScanResult {}

  record Failed() implements KeyframePacketScanResult {}
}
