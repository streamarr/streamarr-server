package com.streamarr.server.domain.streaming;

/** The delivery for which the server checked a capability entry (ADR 0049). */
public enum CapabilityEntryDelivery {
  /** Direct play of the source file from its source container (ADR 0045). */
  DIRECT_PLAY,
  /** A stream copy over HLS. */
  HLS
}
