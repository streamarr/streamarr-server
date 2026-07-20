package com.streamarr.server.services.streaming;

import java.util.Arrays;

/** Terminal outcome of one segment delivery attempt (ADR 0019). */
public sealed interface SegmentDelivery {

  /** The segment's bytes; atomic publication guarantees existence implies completeness. */
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

  /** The session was destroyed or never existed; the request maps to 404. */
  record SessionEnded() implements SegmentDelivery {}

  /** Recovery exhausted every snapshotted execution target; the request maps to 503. */
  record Unrecoverable() implements SegmentDelivery {}

  /** The waiting thread was interrupted (server lifecycle); the response is moot. */
  record Cancelled() implements SegmentDelivery {}
}
