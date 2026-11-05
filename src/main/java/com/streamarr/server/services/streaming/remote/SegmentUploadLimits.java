package com.streamarr.server.services.streaming.remote;

/** The limits within which the server admits segment uploads from workers. */
final class SegmentUploadLimits {

  static final int MAXIMUM_CONCURRENT_SEGMENT_UPLOADS = 32;
  static final long MAXIMUM_BUFFERED_SEGMENT_BYTES = 512L * 1024 * 1024;

  // A ladder's variants upload in parallel, so the allowance has to clear a full ladder; it is a
  // blast-radius bound on one worker's wedged streams, not a throughput limit.
  static final int MAXIMUM_SEGMENT_UPLOADS_PER_WORKER = 8;

  private SegmentUploadLimits() {}
}
