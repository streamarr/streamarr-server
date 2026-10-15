package com.streamarr.server.services.concurrency;

/** The one outcome that an admitted task reports, which returns its admission. */
public enum AdmittedTaskReport {
  /** The task did real processing, so its latency teaches the limit. */
  LATENCY,
  /** The task returned without real processing, so its latency says nothing about capacity. */
  IGNORED,
  /** The task hit a retry or a timeout. The limit reads its latency as an ordinary sample. */
  DROP
}
