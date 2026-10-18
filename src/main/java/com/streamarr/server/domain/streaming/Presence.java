package com.streamarr.server.domain.streaming;

/** Whether a dynamic-range feature is present. UNSPECIFIED means that the probe could not check. */
public enum Presence {
  UNSPECIFIED,
  ABSENT,
  PRESENT
}
