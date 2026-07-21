package com.streamarr.server.services.streaming;

import java.util.Arrays;

/** Terminal outcome of one segment delivery attempt (ADR 0019). */
public sealed interface SegmentDelivery {

  /**
   * The segment's bytes; atomic publication guarantees existence implies completeness. The array is
   * handed off at construction — neither party mutates it afterwards; no defensive copy is taken
   * (segments run to 16 MB).
   */
  record Ready(byte[] data) implements SegmentDelivery {

    @Override
    public boolean equals(Object other) {
      return other instanceof Ready(byte[] otherData) && Arrays.equals(data, otherData);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
      return "Ready[" + data.length + " bytes]";
    }
  }

  /**
   * The session — or the requested variant or segment name — was destroyed, never existed, or names
   * nothing a run can produce; the request maps to 404.
   */
  record SessionEnded() implements SegmentDelivery {}

  /**
   * Recovery exhausted every eligible execution target, each consulted live at dispatch time (never
   * a snapshot — ADR 0019); the request maps to 503.
   */
  record Unrecoverable() implements SegmentDelivery {}

  /** The waiting thread was interrupted (server lifecycle); the request maps to 503. */
  record Cancelled() implements SegmentDelivery {}
}
