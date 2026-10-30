package com.streamarr.server.services.streaming;

import java.util.Arrays;

/** Terminal outcome of one segment delivery attempt (ADR 0019). */
public sealed interface SegmentDelivery {

  /**
   * The segment's bytes; publication exposes the final name only after all bytes are written. The
   * array is handed off at construction — neither party mutates it afterwards; no defensive copy is
   * taken because segments can be large.
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
   * nothing the playlist advertises; the request maps to 404.
   */
  record SessionEnded() implements SegmentDelivery {}

  /**
   * No job attempt can produce the segment for this request; the request maps to 503. Either
   * recovery exhausted every eligible execution target, each consulted live at dispatch time (never
   * a snapshot — ADR 0019), or the variant has no job attempt because its on-demand start failed or
   * a request for another video variant stopped it, or a newer request for the variant started a
   * job attempt that will not produce the segment.
   */
  record Unrecoverable() implements SegmentDelivery {}

  /** The waiting thread was interrupted (server lifecycle); the request maps to 503. */
  record Cancelled() implements SegmentDelivery {}
}
