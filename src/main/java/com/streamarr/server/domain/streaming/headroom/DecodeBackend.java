package com.streamarr.server.domain.streaming.headroom;

/** Whether a job attempt decodes its source in hardware or in software. */
public enum DecodeBackend {
  SOFTWARE,
  HARDWARE
}
