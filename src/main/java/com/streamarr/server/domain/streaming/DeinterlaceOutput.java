package com.streamarr.server.domain.streaming;

/** How many frames the worker's deinterlacer writes. */
public enum DeinterlaceOutput {
  /** One frame for each field: a source of 29.97 frames, 59.94 fields, per second runs at 59.94. */
  ONE_FRAME_PER_FIELD,
  /** One frame for each source frame: a source of 29.97 frames per second stays at 29.97. */
  ONE_FRAME_PER_FRAME
}
