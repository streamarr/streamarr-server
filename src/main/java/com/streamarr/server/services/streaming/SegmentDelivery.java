package com.streamarr.server.services.streaming;

import lombok.NonNull;

/** Terminal outcome of one segment delivery attempt (ADR 0019). */
public sealed interface SegmentDelivery {

  /**
   * The published segment, opened for reading. Whoever receives the delivery closes the segment
   * when its answer ends.
   */
  record Ready(@NonNull StoredSegment segment) implements SegmentDelivery {}

  /**
   * The session — or the requested variant or segment name — was destroyed, never existed, or names
   * nothing the playlist advertises; the request maps to 404.
   */
  record SessionEnded() implements SegmentDelivery {}

  /**
   * No job attempt can produce the segment for this request; the request maps to 503. Either
   * recovery exhausted every eligible execution target, each consulted live at dispatch time (never
   * a snapshot — ADR 0019), or the variant has no job attempt because its on-demand start failed or
   * a request for another video variant stopped it, or a newer request positioned the variant where
   * its job attempt will not produce the segment, or the segment lies past a copy that ran short
   * while a source stream still has media.
   */
  record Unrecoverable() implements SegmentDelivery {}

  /** The waiting thread was interrupted (server lifecycle); the request maps to 503. */
  record Cancelled() implements SegmentDelivery {}
}
