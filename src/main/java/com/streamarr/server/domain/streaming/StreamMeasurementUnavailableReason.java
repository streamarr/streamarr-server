package com.streamarr.server.domain.streaming;

/** Why a file's streams have no measurement for its source snapshot (ADR 0054). */
public enum StreamMeasurementUnavailableReason {
  /** Three attempts of one bitrate scan chunk ended without a result. */
  FAILED_ATTEMPTS,
  /** A stream copy would change the size or the number of a measured stream's packets. */
  COPY_CHANGES_PACKETS
}
