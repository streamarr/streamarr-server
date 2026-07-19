package com.streamarr.server.services.streaming;

/** Terminal outcome of one segment delivery attempt (ADR 0019). */
public sealed interface SegmentDelivery {

  /** The segment's bytes; atomic publication guarantees existence implies completeness. */
  record Ready(byte[] data) implements SegmentDelivery {}

  /** The session was destroyed or never existed; the request maps to 404. */
  record SessionEnded() implements SegmentDelivery {}

  /** Recovery exhausted every snapshotted execution target; the request maps to 503. */
  record Unrecoverable() implements SegmentDelivery {}

  /** The waiting thread was interrupted (server lifecycle); the response is moot. */
  record Cancelled() implements SegmentDelivery {}
}
