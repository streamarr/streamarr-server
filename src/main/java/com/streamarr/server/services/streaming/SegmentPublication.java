package com.streamarr.server.services.streaming;

/** The outcome of publishing a prepared segment. */
public enum SegmentPublication {
  PUBLISHED,
  /**
   * The variant already stores a different initialization segment. Every attempt of a variant must
   * produce the same one, so the stored segment stays untouched and the new bytes are never served.
   */
  INITIALIZATION_SEGMENT_DIFFERS
}
